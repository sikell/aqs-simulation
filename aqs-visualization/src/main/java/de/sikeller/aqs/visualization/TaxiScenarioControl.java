package de.sikeller.aqs.visualization;

import de.sikeller.aqs.model.SimulationControl;
import java.awt.*;
import java.util.*;
import javax.swing.*;

public class TaxiScenarioControl extends JPanel {
  private final SimulationControl simulation;
  private Map<String, Integer> inputParameters;
  private JPanel buttons;
  private JPanel inputs;

  public TaxiScenarioControl(SimulationControl simulation) {
    this.simulation = simulation;
    add(setup());
  }

  private JPanel setup() {
    JPanel controls = new JPanel();
    buttons = new JPanel();
    inputs = new JPanel();
    JButton startButton = startButton();
    buttons.add(startButton);
    buttons.putClientProperty("startButton", startButton);
    buttons.add(stopButton());
    buttons.add(initializeSimulationButton());
    Set<String> parameters = simulation.getSimulationParameters();
    inputParameters = new HashMap<>();
    parameters.forEach(
        parameter -> {
          inputs.add(new JLabel(parameter));
          JTextField textField = new JTextField();
          textField.setColumns(4);
          inputs.add(textField);
          inputParameters.put(parameter, 0);
        });
    controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
    inputs.setLayout(new GridLayout(5, 2));
    controls.add(buttons);
    controls.add(inputs);
    return controls;
  }

  private JButton startButton() {
    JButton button = new JButton("Start");
    button.setEnabled(false);
    button.addActionListener(e -> simulation.start());
    return button;
  }

  private JButton stopButton() {
    JButton button = new JButton("Stop");
    button.addActionListener(e -> simulation.stop());
    return button;
  }

  private JButton initializeSimulationButton() {
    JButton button = new JButton("Initialize Simulation");
    button.addActionListener(
        e -> {
          buttons.getComponent(0).setEnabled(true);
          button.setEnabled(false);
          Set<Integer> input = new HashSet<>();
          for (Component component : inputs.getComponents()) {
              if (component instanceof JTextField textField) {
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
}
