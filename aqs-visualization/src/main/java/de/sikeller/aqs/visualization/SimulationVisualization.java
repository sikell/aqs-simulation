package de.sikeller.aqs.visualization;

import de.sikeller.aqs.model.SimulationControl;
import de.sikeller.aqs.model.SimulationObserver;
import de.sikeller.aqs.model.World;
import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import java.awt.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
public class SimulationVisualization extends AbstractVisualization implements SimulationObserver {
  private final TaxiScenarioCanvas canvas;
  private final ExecutorService service = Executors.newSingleThreadExecutor();

  public SimulationVisualization(World world, SimulationControl simulation) {
    super("Taxi Scenario Simulation");
    var controls = new JPanel();
    controls.setLayout(new GridLayout(2, 1));
    controls.add(new TaxiScenarioControl(simulation));
    var visuProperties = new VisualizationProperties();
    controls.add(new VisualizationControl(visuProperties));

    canvas = new TaxiScenarioCanvas(world, visuProperties);

    frame.setLayout(new GridLayout(1, 2));
    frame.add(controls);
    frame.add(canvas);
    frame.setVisible(true);
    frame.pack();
  }

  @Override
  public void onUpdate(World world) {
    var snapshot = world.snapshot();
    service.submit(
        () -> {
          canvas.update(snapshot);
          if (!frame.isVisible()) {
            frame.setVisible(true);
          }
        });
  }
}
