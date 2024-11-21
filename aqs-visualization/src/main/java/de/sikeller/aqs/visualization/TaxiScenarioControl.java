package de.sikeller.aqs.visualization;

import de.sikeller.aqs.model.SimulationControl;
import java.awt.*;
import javax.swing.*;

public class TaxiScenarioControl extends JPanel {
  private final SimulationControl simulation;

  public TaxiScenarioControl(SimulationControl simulation) {
    this.simulation = simulation;
    add(startButton());
    add(stopButton());
    setMinimumSize(new Dimension(100, 100));
  }

  private JButton startButton() {
    JButton button = new JButton("Start");
    button.addActionListener(e -> simulation.start());
    return button;
  }

  private JButton stopButton() {
    JButton button = new JButton("Stop");
    button.addActionListener(e -> simulation.stop());
    return button;
  }
}
