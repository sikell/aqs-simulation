package de.sikeller.aqs.visualization.controls;

import de.sikeller.aqs.model.AlgorithmParameter;
import de.sikeller.aqs.model.SimulationControl;
import de.sikeller.aqs.model.TaxiAlgorithm;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Properties;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TaxiScenarioControl extends AbstractControl {
  private List<Class<?>> algorithmList;
  private final SimulationControl simulation;
  private Map<String, Integer> inputParameterMap;
  private Map<String, Integer> algorithmParameterMap;
  private Map<String, Integer> allParameterMap = new HashMap<>();
  private HashMap<String, Component> componentMap;
  private BatchProcessingProperties batchProperties = new BatchProcessingProperties();
  private JPanel buttons;
  private JPanel worldInputs;
  private JPanel algorithmInputs;
  private JPanel controls;
  private JPanel selection;
  private JPanel batchProcessing;

  public TaxiScenarioControl(SimulationControl simulation) {
    this.simulation = simulation;
    add(setup());
  }

  private JPanel setup() {
    controls = new JPanel();
    buttons = new JPanel();
    worldInputs = new JPanel();
    algorithmInputs = new JPanel();
    algorithmParameterMap = new HashMap<>();
    selection = new JPanel();
    buttons.add(initializeSimulationButton());
    buttons.add(startButton());
    buttons.add(stopButton());
    buttons.add(copyConfigButton());
    buttons.add(pasteConfigButton());
    buttons.add(showResultsButton());
    selection.add(label("Select algorithm", "algoSelectionLabel"));
    selection.add(algorithmSelectionBox());
    selection.add(algorithmSelectionButton());
    worldInputs.add(label("World seed", "worldSeed"));
    worldInputs.add(
        spinner(
            "worldSeed",
            "Set a simulation random seed",
            1,
            Integer.MAX_VALUE,
            new Random().nextInt(Integer.MAX_VALUE),
            1));
    worldInputs.add(label("Taxi count", "taxiCountLabel"));
    worldInputs.add(
        spinner(
            "taxiCount",
            "Set the count of taxis to be spawned in the simulation run",
            1,
            1_000_000_000,
            5,
            1));
    worldInputs.add(label("Client count", "clientCountLabel"));
    worldInputs.add(clientCountSpinner());
    worldInputs.add(label("Client spawn window [s]", "clientSpawnWindowLabel"));
    worldInputs.add(clientSpawnWindowSpinner());
    worldInputs.add(label("Client speed [km/h]", "clientSpeed"));
    worldInputs.add(clientSpeedSpinner());
    worldInputs.add(label("Taxi seats", "taxiSeatCount"));
    worldInputs.add(taxiSeatCountSpinner());
    worldInputs.add(label("Taxi speed [km/h]", "taxiSpeed"));
    worldInputs.add(taxiSpeedSpinner());
    worldInputs.add(label("Simulation speed", "simulationSpeedLabel"));
    worldInputs.add(simulationSpeed());
    worldInputs.setBorder(new TitledBorder("World Parameters"));
    controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
    worldInputs.setLayout(new GridLayout(8, 2, GAP, GAP));
    batchProcessing = new BatchProcessingControl(batchProperties);
    controls.add(selection);
    controls.add(buttons);
    controls.add(worldInputs);
    controls.add(algorithmInputs);
    controls.add(batchProcessing);

    createComponentMap();

    return controls;
  }

  private JComboBox<String> algorithmSelectionBox() {
    algorithmList = simulation.getAlgorithm().getAllAlgorithms();
    List<String> choices = new ArrayList<>();
    algorithmList.forEach(entry -> choices.add(entry.getSimpleName()));
    JComboBox<String> algorithms = new JComboBox<>();
    algorithms.setName("algorithmSelectionBox");
    choices.forEach(algorithms::addItem);
    algorithms.setSelectedItem(simulation.getAlgorithm().get().getClass().getSimpleName());
    return algorithms;
  }

  private JButton algorithmSelectionButton() {
    JButton button = new JButton("Select");
    button.setName("algorithmSelectionButton");
    button.addActionListener(
        e -> {
          JComboBox<String> selection =
              (JComboBox<String>) getComponentByName("algorithmSelectionBox");
          String selectedItem;

          if (selection != null) {
            selectedItem = Objects.requireNonNull(selection.getSelectedItem()).toString();
            String selectedAlgorithm = "";

            for (Class<?> object : algorithmList) {
              if (object.getSimpleName().matches(selectedItem)) {
                selectedAlgorithm = object.getName();
              }
            }

            simulation
                .getAlgorithm()
                .setAlgorithm(instantiateAlgorithm(selectedAlgorithm, algorithmParameterMap));
          }
          generateParameters();
        });
    generateParameters();
    return button;
  }

  private JButton startButton() {
    JButton button = new JButton("Start");
    button.setName("startButton");
    button.setEnabled(false);
    button.addActionListener(
        e -> {
          simulation.start();
          button.setEnabled(false);
          Objects.requireNonNull(getComponentByName("initializeSimulationButton")).setEnabled(true);
          Objects.requireNonNull(getComponentByName("stopButton")).setEnabled(true);

          int batchCount = (int) ((JSpinner) getComponentByName("batchCount")).getValue();
          if (batchCount == 1) {
            simulation.start();
          } else {
            new Thread(
                    () -> {
                      for (int i = 0; i < batchCount; i++) {
                        simulation.start();
                        do {} while (!simulation.isSimulationFinished());
                        int newTaxiCount =
                            ((int) ((JSpinner) getComponentByName("taxiCount")).getValue())
                                + ((int)
                                    ((JSpinner) getComponentByName("taxiIncrement")).getValue());
                        ((JSpinner) getComponentByName("taxiCount")).setValue(newTaxiCount);
                        int newClientCount =
                            ((int) ((JSpinner) getComponentByName("clientCount")).getValue())
                                + ((int)
                                    ((JSpinner) getComponentByName("clientIncrement")).getValue());
                        ((JSpinner) getComponentByName("clientCount")).setValue(newClientCount);
                        int newTaxiSeatCount =
                            ((int) ((JSpinner) getComponentByName("taxiSeatCount")).getValue())
                                + ((int)
                                    ((JSpinner) getComponentByName("taxiSeatIncrement"))
                                        .getValue());
                        ((JSpinner) getComponentByName("taxiSeatCount")).setValue(newTaxiSeatCount);
                        initializeSimulation();
                      }
                    })
                .start();
          }
        });
    return button;
  }

  private JButton stopButton() {
    JButton button = new JButton("Stop");
    button.setName("stopButton");
    button.setEnabled(false);
    button.addActionListener(
        e -> {
          simulation.stop();
          Objects.requireNonNull(getComponentByName("startButton")).setEnabled(true);
        });
    return button;
  }

  private JButton copyConfigButton() {
    JButton button = new JButton("Copy Config");
    button.setName("copyConfigButton");
    button.addActionListener(e -> copyConfigToClipboard());
    return button;
  }

  private JButton pasteConfigButton() {
    JButton button = new JButton("Paste Config");
    button.setName("pasteConfigButton");
    button.addActionListener(e -> pasteConfigFromClipboard());
    return button;
  }

  private JButton showResultsButton() {
    JButton button = new JButton("Show Results");
    button.setName("showResultsButton");
    button.addActionListener(e -> simulation.showResultVisualization());
    return button;
  }

  @SuppressWarnings(value = "BusyWait")
  private JButton initializeSimulationButton() {
    JButton button = new JButton("Initialize");
    button.setName("initializeSimulationButton");
    button.addActionListener(e -> initializeSimulation());
    return button;
  }

  private JSlider simulationSpeed() {
    JSlider slider = new JSlider();
    slider.setName("simulationSpeedSlider");
    slider.setToolTipText("Speed of simulation");
    slider.setMaximum(100);
    slider.setMinimum(1);
    slider.setValue(simulation.getSpeed());
    slider.addChangeListener(
        e -> {
          JSlider source = (JSlider) e.getSource();
          if (!source.getValueIsAdjusting()) {
            int value = source.getValue();
            simulation.setSpeed(value);
          }
        });
    return slider;
  }

  private JSpinner clientCountSpinner() {
    SpinnerModel spinnerModel = new SpinnerNumberModel(100, 1, 1_000_000_000, 1);
    JSpinner spinner = new JSpinner(spinnerModel);
    spinner.setName("clientCount");
    spinner.setToolTipText("Set the Count of Clients for the Simulation");
    return spinner;
  }

  private JSpinner clientSpawnWindowSpinner() {
    SpinnerModel spinnerModel = new SpinnerNumberModel(100_00, 1, 1_000_000_000, 1);
    JSpinner spinner = new JSpinner(spinnerModel);
    spinner.setName("clientSpawnWindow");
    spinner.setToolTipText("Set the spawn time window in which clients can randomly spawn");
    return spinner;
  }

  private JSpinner taxiSeatCountSpinner() {
    SpinnerModel spinnerModel = new SpinnerNumberModel(2, 1, 1_000_000_000, 1);
    JSpinner spinner = new JSpinner(spinnerModel);
    spinner.setName("taxiSeatCount");
    spinner.setToolTipText("Set the Count of seats in the Taxi");
    return spinner;
  }

  private JSpinner clientSpeedSpinner() {
    SpinnerModel spinnerModel = new SpinnerNumberModel(5, 0, 1_000_000_000, 1);
    JSpinner spinner = new JSpinner(spinnerModel);
    spinner.setName("clientSpeed");
    spinner.setToolTipText("Set the initial Speed of a Client");
    return spinner;
  }

  private JSpinner taxiSpeedSpinner() {
    SpinnerModel spinnerModel = new SpinnerNumberModel(80, 1, 1_000_000_000, 1);
    JSpinner spinner = new JSpinner(spinnerModel);
    spinner.setName("taxiSpeed");
    spinner.setToolTipText("Set the initial Speed of the Taxi");
    return spinner;
  }

  private void createComponentMap() {
    componentMap = new LinkedHashMap<>();
    List<Component> components = new ArrayList<>();
    Collections.addAll(components, controls.getComponents());
    Collections.addAll(components, buttons.getComponents());
    Collections.addAll(components, worldInputs.getComponents());
    Collections.addAll(components, selection.getComponents());
    Collections.addAll(components, batchProcessing.getComponents());
    for (Component component : components) {
      componentMap.put(component.getName(), component);
    }
  }

  private Component getComponentByName(String componentName) {
    if (componentMap.containsKey(componentName)) {
      return componentMap.get(componentName);
    }
    return null;
  }

  private static TaxiAlgorithm instantiateAlgorithm(
      String algorithmName, Map<String, Integer> parameters) {
    try {
      Class<?> algorithmClassName = Class.forName(algorithmName);
      TaxiAlgorithm algorithm =
          (TaxiAlgorithm) algorithmClassName.getDeclaredConstructor().newInstance();
      algorithm.setParameters(parameters);
      return algorithm;
    } catch (ClassNotFoundException
        | InvocationTargetException
        | InstantiationException
        | IllegalAccessException
        | NoSuchMethodException e) {
      throw new RuntimeException(e);
    }
  }

  private void generateParameters() {
    Set<AlgorithmParameter> parameters = simulation.getSimulationParameters().getParameters();
    if (parameters.isEmpty()) {
      algorithmInputs.setBorder(null);
    } else {
      algorithmInputs.setBorder(new TitledBorder("Algorithm Parameters"));
    }
    algorithmInputs.removeAll();
    if (!algorithmParameterMap.isEmpty()) {
      algorithmParameterMap.clear();
    }
    inputParameterMap = new HashMap<>();
    inputParameterMap.put("taxiCount", 0);
    inputParameterMap.put("clientCount", 0);
    parameters.forEach(
        parameter -> {
          JLabel label = new JLabel(parameter.name());
          label.setName(parameter + "Label");
          algorithmInputs.add(label);
          int defaultValue = parameter.defaultValue() != null ? parameter.defaultValue() : 1;
          SpinnerModel model = new SpinnerNumberModel(defaultValue, 0, Integer.MAX_VALUE, 1);
          JSpinner spinner = new JSpinner(model);
          label.setLabelFor(spinner);
          spinner.setName(parameter.name());
          algorithmInputs.add(spinner);
          inputParameterMap.put(parameter.name(), 0);
        });
    algorithmInputs.setLayout(new GridLayout(parameters.size(), 2, GAP, GAP));
    SwingUtilities.updateComponentTreeUI(worldInputs);
  }

  private void initializeSimulation() {
    simulation.stop();
    JButton startButton = (JButton) getComponentByName("startButton");
    try {
      for (Component component : worldInputs.getComponents()) {
        if (component instanceof JTextField textField) {
          allParameterMap.put(textField.getName(), Integer.parseInt(textField.getText()));
        }
        if (component instanceof JSpinner spinner) {
          allParameterMap.put(spinner.getName(), (int) spinner.getValue());
        }
      }

      for (Component component : algorithmInputs.getComponents()) {
        if (component instanceof JSpinner spinner) {
          allParameterMap.put(spinner.getName(), (int) spinner.getValue());
          algorithmParameterMap.put(spinner.getName(), (int) spinner.getValue());
        }
      }

      inputParameterMap.putAll(allParameterMap);

      startButton.setEnabled(true);

      simulation.init(inputParameterMap);
    } catch (Exception exception) {
      log.error(exception.getMessage(), exception);
      JOptionPane.showMessageDialog(this, "No valid input parameters provided!");
    }
  }

  private void copyConfigToClipboard() {
    Properties props = new Properties();

    // Algorithm Selection
    JComboBox<String> algoComboBox =
        (JComboBox<String>) getComponentByName("algorithmSelectionBox");
    if (algoComboBox != null) {
      props.setProperty("algorithmSelectionBox", (String) algoComboBox.getSelectedItem());
    }

    // World Parameters
    for (Component comp : worldInputs.getComponents()) {
      if (comp instanceof JSpinner spinner && comp.getName() != null) {
        props.setProperty(spinner.getName(), spinner.getValue().toString());
      }
      if (comp instanceof JSlider slider && comp.getName() != null) {
        props.setProperty(slider.getName(), String.valueOf(slider.getValue()));
      }
    }

    // dynamic Algorithm Parameters
    for (Component comp : algorithmInputs.getComponents()) {
      if (comp instanceof JSpinner spinner && comp.getName() != null) {
        props.setProperty(spinner.getName(), spinner.getValue().toString());
      }
    }

    // Batch Processing Parameters
    for (Component comp : batchProcessing.getComponents()) {
      if (comp instanceof JSpinner spinner && comp.getName() != null) {
        props.setProperty(spinner.getName(), spinner.getValue().toString());
      }
    }

    try (StringWriter writer = new StringWriter()) {
      props.store(writer, "AQS Simulation Configuration");
      String configString = writer.toString();

      StringSelection stringSelection = new StringSelection(configString);
      Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
      clipboard.setContents(stringSelection, null);
      JOptionPane.showMessageDialog(this, "Configuration copied to clipboard!");
      log.debug("Configuration copied to clipboard:\n{}", configString);

    } catch (IOException ex) {
      log.error("Error writing configuration to string", ex);
      JOptionPane.showMessageDialog(
          this,
          "Error copying configuration: " + ex.getMessage(),
          "Error",
          JOptionPane.ERROR_MESSAGE);
    }
  }

  private void pasteConfigFromClipboard() {
    try {
      Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
      String configString = (String) clipboard.getData(DataFlavor.stringFlavor);

      if (configString == null || configString.trim().isEmpty()) {
        JOptionPane.showMessageDialog(
            this,
            "Clipboard is empty or contains no text.",
            "Info",
            JOptionPane.INFORMATION_MESSAGE);
        return;
      }

      Properties props = new Properties();
      try (StringReader reader = new StringReader(configString)) {
        props.load(reader);
      }

      String algoNameFromProps = props.getProperty("algorithmSelectionBox");
      boolean algorithmChanged = false;

      if (algoNameFromProps != null) {
        JComboBox<String> algoComboBox =
            (JComboBox<String>) getComponentByName("algorithmSelectionBox");
        if (algoComboBox != null) {
          if (!algoNameFromProps.equals(algoComboBox.getSelectedItem())) {
            String selectedAlgorithmFullName = "";
            for (Class<?> algoClass : algorithmList) {
              if (algoClass.getSimpleName().equals(algoNameFromProps)) {
                selectedAlgorithmFullName = algoClass.getName();
                break;
              }
            }
            if (!selectedAlgorithmFullName.isEmpty()) {
              // instantiate the algorithm with empty parameters
              simulation
                  .getAlgorithm()
                  .setAlgorithm(instantiateAlgorithm(selectedAlgorithmFullName, new HashMap<>()));
              algoComboBox.setSelectedItem(algoNameFromProps);
              generateParameters();
              log.info("Algorithm set to {} and parameters regenerated.", algoNameFromProps);
              algorithmChanged = true;
            } else {
              log.warn(
                  "Pasted algorithm name '{}' not found in available algorithms.",
                  algoNameFromProps);
            }
          } else {
            generateParameters();
            algorithmChanged = true;
          }
        }
      }

      // generate Parameters
      if (!algorithmChanged) {
        generateParameters();
      }

      for (String name : props.stringPropertyNames()) {
        if (name.equals("algorithmSelectionBox")) continue; // already handled above

        String valueStr = props.getProperty(name);
        boolean valueSet = false;

        // 1. try: set algorithm parameters
        if (algorithmInputs != null) {
          for (Component compInAlgoPanel : algorithmInputs.getComponents()) {
            if (compInAlgoPanel instanceof JSpinner && name.equals(compInAlgoPanel.getName())) {
              try {
                ((JSpinner) compInAlgoPanel).setValue(Integer.parseInt(valueStr));
                log.trace("Set ALGORITHM JSpinner '{}' to '{}'", name, valueStr);
                valueSet = true;
                break;
              } catch (NumberFormatException nfe) {
                log.warn(
                    "Could not parse '{}' as integer for ALGORITHM spinner '{}'", valueStr, name);
              }
            }
            if (compInAlgoPanel instanceof JSlider && name.equals(compInAlgoPanel.getName())) {
              try {
                ((JSlider) compInAlgoPanel).setValue(Integer.parseInt(valueStr));
                log.trace("Set ALGORITHM JSlider '{}' to '{}'", name, valueStr);
                valueSet = true;
                break;
              } catch (NumberFormatException nfe) {
                log.warn(
                    "Could not parse '{}' as integer for ALGORITHM slider '{}'", valueStr, name);
              }
            }
          }
        }

        // 2. try: set global parameters
        if (!valueSet) {
          Component generalComp = getComponentByName(name);
          if (generalComp instanceof JSpinner) {
            try {
              ((JSpinner) generalComp).setValue(Integer.parseInt(valueStr));
              log.trace("Set GENERAL JSpinner '{}' to '{}'", name, valueStr);
              valueSet = true;
            } catch (NumberFormatException nfe) {
              log.warn("Could not parse '{}' as integer for GENERAL spinner '{}'", valueStr, name);
            }
          }
          if (generalComp instanceof JSlider) {
            try {
              ((JSlider) generalComp).setValue(Integer.parseInt(valueStr));
              log.trace("Set GENERAL JSlider '{}' to '{}'", name, valueStr);
              valueSet = true;
            } catch (NumberFormatException nfe) {
              log.warn("Could not parse '{}' as integer for GENERAL slider '{}'", valueStr, name);
            }
          }
        }

        if (!valueSet) {
          log.warn(
              "Pasted parameter '{}' with value '{}' could not be applied to any known JSpinner.",
              name,
              valueStr);
        }
      }

      // refresh the UI to show the new values
      SwingUtilities.updateComponentTreeUI(this);
      JOptionPane.showMessageDialog(this, "Configuration pasted from clipboard!");

    } catch (UnsupportedFlavorException | IOException ex) {
      log.error("Error pasting configuration from clipboard", ex);
      JOptionPane.showMessageDialog(
          this,
          "Error pasting configuration: " + ex.getMessage(),
          "Error",
          JOptionPane.ERROR_MESSAGE);
    } catch (Exception ex) { // Catch-all for other issues like parsing or component finding
      log.error("Generic error during paste operation", ex);
      JOptionPane.showMessageDialog(
          this,
          "Error applying pasted configuration: " + ex.getMessage(),
          "Error",
          JOptionPane.ERROR_MESSAGE);
    }
  }
}
