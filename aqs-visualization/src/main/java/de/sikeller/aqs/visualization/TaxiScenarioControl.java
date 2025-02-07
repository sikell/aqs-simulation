package de.sikeller.aqs.visualization;

import de.sikeller.aqs.model.SimulationControl;
import de.sikeller.aqs.model.TaxiAlgorithm;

import java.awt.*;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.List;
import javax.swing.*;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TaxiScenarioControl extends AbstractControl {
  private List<Class<?>> algorithmList;
  private final SimulationControl simulation;
  private Map<String, Integer> inputParameters;
  private Map<String, Integer> algorithmParameters;
  private HashMap<String, Component> componentMap;
  private JPanel buttons;
  private JPanel worldInputs;
  private JPanel algorithmInputs;
  private JPanel controls;
  private JPanel selection;

  public TaxiScenarioControl(SimulationControl simulation) {
    this.simulation = simulation;
    add(setup());
  }

  private JPanel setup() {
    controls = new JPanel();
    buttons = new JPanel();
    worldInputs = new JPanel();
    algorithmInputs = new JPanel();
    algorithmParameters = new HashMap<>();
    selection = new JPanel();
    buttons.add(initializeSimulationButton());
    buttons.add(startButton());
    buttons.add(stopButton());
    buttons.add(showResultsButton());
    selection.add(label("Select algorithm", "algoSelectionLabel"));
    selection.add(algorithmSelectionBox());
    selection.add(algorithmSelectionButton());
    worldInputs.add(label("Taxi count", "taxiCountLabel"));
    worldInputs.add(taxiCountTextField());
    worldInputs.add(label("Client count", "clientCountLabel"));
    worldInputs.add(clientCountTextField());
    worldInputs.add(label("Taxi seats", "taxiSeatCount"));
    worldInputs.add(taxiSeatCountTextField());
    worldInputs.add(label("Taxi speed", "taxiSpeed"));
    worldInputs.add(taxiSpeedTextField());
    worldInputs.add(label("Simulation speed", "simulationSpeedLabel"));
    worldInputs.add(simulationSpeed());
    controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
    worldInputs.setLayout(new GridLayout(5, 2));

    controls.add(selection);
    controls.add(buttons);
    controls.add(worldInputs);
    controls.add(algorithmInputs);

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
    algorithms.setSelectedItem(simulation.getAlgorithm().getAlgorithm().getClass().getSimpleName());
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

          System.out.println(selection);
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
                .setAlgorithm(instantiateAlgorithm(selectedAlgorithm, algorithmParameters));
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

  private JTextField taxiCountTextField() {
    JTextField textField = new JTextField();
    textField.setName("taxiCount");
    textField.setColumns(4);
    textField.setText("5");
    textField.setToolTipText("Set the Count of Taxis for the Simulation");
    return textField;
  }

  private JTextField clientCountTextField() {
    JTextField textField = new JTextField();
    textField.setName("clientCount");
    textField.setColumns(4);
    textField.setText("100");
    textField.setToolTipText("Set the Count of Clients for the Simulation");
    return textField;
  }

  private JTextField taxiSeatCountTextField() {
    JTextField textField = new JTextField();
    textField.setName("taxiSeatCount");
    textField.setColumns(4);
    textField.setText("2");
    textField.setToolTipText("Set the Count of seats in the Taxi");
    return textField;
  }

  private JTextField taxiSpeedTextField() {
    JTextField textField = new JTextField();
    textField.setName("taxiSpeed");
    textField.setColumns(4);
    textField.setText("1");
    textField.setToolTipText("Set the initial Speed of the Taxi");
    return textField;
  }

  private JButton showResultsButton() {
    JButton button = new JButton("Show Results");
    button.setName("showResultsButton");
    button.addActionListener(e -> simulation.showResultVisualization());
    return button;
  }

  private JButton initializeSimulationButton() {
    JButton button = new JButton("Initialize");
    button.setName("initializeSimulationButton");

    button.addActionListener(
        e -> {
          simulation.stop();
          Component startButton = getComponentByName("startButton");
          if (startButton == null) return;
          try {
            Map<String, Integer> input = new HashMap<>();
            for (Component component : worldInputs.getComponents()) {
              if (component instanceof JTextField textField) {
                System.out.println(textField.getName());
                System.out.println(textField.getText());
                input.put(textField.getName(), Integer.parseInt(textField.getText()));
              }
            }

            for (Component component : algorithmInputs.getComponents()) {
              if (component instanceof JTextField textField) {
                System.out.println(textField.getName());
                System.out.println(textField.getText());
                input.put(textField.getName(), Integer.parseInt(textField.getText()));
                algorithmParameters.put(textField.getName(), Integer.parseInt(textField.getText()));
              }
            }

            inputParameters.putAll(input);

            startButton.setEnabled(true);

            simulation.init(inputParameters);
          } catch (Exception exception) {
            log.error(exception.getMessage(), exception);
            JOptionPane.showMessageDialog(this, "No valid input parameters provided!");
          }
          simulation.setSimulationFinished(true);
        });
    return button;
  }

  private void createComponentMap() {
    componentMap = new HashMap<>();
    List<Component> components = new ArrayList<>();
    Collections.addAll(components, controls.getComponents());
    Collections.addAll(components, buttons.getComponents());
    Collections.addAll(components, worldInputs.getComponents());
    Collections.addAll(components, selection.getComponents());
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
    Set<String> parameters = simulation.getSimulationParameters().getParameters();
    algorithmInputs.removeAll();
    if (!algorithmParameters.isEmpty()) {
      algorithmParameters.clear();
    }
    inputParameters = new HashMap<>();
    inputParameters.put("taxiCount", 0);
    inputParameters.put("clientCount", 0);
    parameters.forEach(
        parameter -> {
          JLabel label = new JLabel(parameter);
          label.setName(parameter + "Label");
          algorithmInputs.add(label);
          JTextField textField = new JTextField();
          label.setLabelFor(textField);
          textField.setColumns(4);
          textField.setName(parameter);
          algorithmInputs.add(textField);
          inputParameters.put(parameter, 0);
        });
    SwingUtilities.updateComponentTreeUI(worldInputs);
  }
}
