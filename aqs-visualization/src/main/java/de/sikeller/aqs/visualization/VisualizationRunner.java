package de.sikeller.aqs.visualization;

import de.sikeller.aqs.model.World;
import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
public class VisualizationRunner {
  private final TaxiScenarioCanvas canvas;
  private final JFrame frame;
  private final ExecutorService service = Executors.newSingleThreadExecutor();

  public VisualizationRunner(World world) {
    this.canvas = new TaxiScenarioCanvas(world);
    frame = new JFrame("Taxi Scenario");
    frame.add(canvas);
    frame.pack();
  }

  public void update(World world) {
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
