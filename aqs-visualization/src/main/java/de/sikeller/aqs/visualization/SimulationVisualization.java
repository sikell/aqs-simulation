package de.sikeller.aqs.visualization;

import de.sikeller.aqs.model.SimulationControl;
import de.sikeller.aqs.model.SimulationObserver;
import de.sikeller.aqs.model.World;
import lombok.extern.slf4j.Slf4j;

import java.awt.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
public class SimulationVisualization extends AbstractVisualization implements SimulationObserver {
  private final TaxiScenarioCanvas canvas;
  private final ExecutorService service = Executors.newSingleThreadExecutor();

  public SimulationVisualization(World world, SimulationControl simulation) {
    super("Taxi Scenario Simulation");
    canvas = new TaxiScenarioCanvas(world);
    var control = new TaxiScenarioControl(simulation);

    frame.setLayout(new GridLayout(1, 2));
    frame.add(control);
    frame.add(canvas);

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
