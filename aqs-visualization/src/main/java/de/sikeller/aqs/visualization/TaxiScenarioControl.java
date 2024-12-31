package de.sikeller.aqs.visualization;

import de.sikeller.aqs.model.SimulationControl;
import de.sikeller.aqs.taxi.algorithm.TaxiAlgorithmFillAllSeats;
import org.reflections.Reflections;
import org.reflections.scanners.SubTypesScanner;
import org.reflections.util.FilterBuilder;

import java.awt.*;
import java.util.*;
import java.util.List;
import javax.swing.*;

public class TaxiScenarioControl extends JPanel {

    private List<Class<?>> algorithmList;
  private final SimulationControl simulation;
  private Map<String, Integer> inputParameters;
  private HashMap<String, Component> componentMap;
  private JPanel buttons;
  private JPanel inputs;
  private JPanel controls;
  private JPanel selection;

  public TaxiScenarioControl(SimulationControl simulation) {
    this.simulation = simulation;
    add(setup());
  }

  private JPanel setup() {
    controls = new JPanel();
    buttons = new JPanel();
    inputs = new JPanel();
    selection = new JPanel();
    buttons.setName("buttons");
    buttons.setName("inputs");
    buttons.add(startButton());
    buttons.add(stopButton());
    buttons.add(initializeSimulationButton());
    selection.add(algorithmSelectionLabel());
    selection.add(algorithmSelectionBox());
    selection.add(algorithmSelectionButton());
    controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
    inputs.setLayout(new GridLayout(5, 2));
      controls.add(selection);
      controls.add(buttons);
      controls.add(inputs);
    createComponentMap();
    return controls;
  }

  private JComboBox<String> algorithmSelectionBox() {
      Reflections reflections =
              new Reflections(
                      "de.sikeller.aqs.taxi.algorithm", new SubTypesScanner(false));
      Set<Class <?>> allClasses = reflections.getSubTypesOf(Object.class);
      algorithmList = new ArrayList<>();
      allClasses.forEach(
              aClass -> {
                  if (aClass.getName().contains("model")) {
                      return;
                  }
                  int mod = aClass.getModifiers();
                  if (mod == 1) {
                      algorithmList.add(aClass);
                  }

              });
      List<String> choices = new ArrayList<>();
      algorithmList.forEach(entry -> choices.add(entry.getSimpleName()));
      JComboBox<String> algorithms = new JComboBox<>();
      algorithms.setName("algorithmSelectionBox");
      choices.forEach(algorithms::addItem);
      return algorithms;
    }

    private JLabel algorithmSelectionLabel() {
      return new JLabel("Selected Algorithm");
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
          } else {
              selectedItem = "empty";
          }

          Set<String> parameters = simulation.getSimulationParameters().getParameters();
          inputs.removeAll();
          inputParameters = new HashMap<>();
          parameters.forEach(
              parameter -> {
                JLabel label = new JLabel(parameter);
                label.setName(parameter + "Label");
                inputs.add(label);
                JTextField textField = new JTextField();
                label.setLabelFor(textField);
                textField.setColumns(4);
                textField.setName(parameter);
                inputs.add(textField);
                inputParameters.put(parameter, 0);
              });
          SwingUtilities.updateComponentTreeUI(inputs);
        });
      return button;
    }

  private JButton startButton() {
    JButton button = new JButton("Start");
    button.setName("startButton");
    button.setEnabled(false);
    button.addActionListener(e -> simulation.start());
    return button;
  }

  private JButton stopButton() {
    JButton button = new JButton("Stop");
    button.setName("stopButton");
    button.addActionListener(e -> simulation.stop());
    return button;
  }

  private JButton initializeSimulationButton() {
    JButton button = new JButton("Initialize Simulation");
    button.setName("initializeSimulationButton");

    button.addActionListener(
        e -> {
          Component startButton = getComponentByName("startButton");
          if (startButton != null) {
            startButton.setEnabled(true);
            button.setEnabled(false);
          }
          List<Integer> input = new ArrayList<>();
          for (Component component : inputs.getComponents()) {
            if (component instanceof JTextField textField) {
              System.out.println(textField.getName());
              System.out.println(textField.getText());
              input.add(Integer.parseInt(textField.getText()));
            }
          }

          Iterator<String> parameterIterator = inputParameters.keySet().iterator();

          for (Integer value : input) {
            if (parameterIterator.hasNext()) {
              String mapKey = parameterIterator.next();
              inputParameters.put(mapKey, value);
            }
          }

          simulation.init(inputParameters);


        });
    return button;
  }

  private void createComponentMap() {
    componentMap = new HashMap<>();
    List<Component> components = new ArrayList<>();
    Collections.addAll(components, controls.getComponents());
    Collections.addAll(components, buttons.getComponents());
    Collections.addAll(components, inputs.getComponents());
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
}
