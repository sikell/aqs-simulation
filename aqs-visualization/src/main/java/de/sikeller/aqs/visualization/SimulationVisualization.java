package de.sikeller.aqs.visualization;

import de.sikeller.aqs.model.World;
import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import java.net.URL;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
public class SimulationVisualization extends AbstractVisualization {
  private final TaxiScenarioCanvas canvas;
  private final ExecutorService service = Executors.newSingleThreadExecutor();

  public SimulationVisualization(World world) {
    super("Taxi Scenario Simulation");
    this.canvas = new TaxiScenarioCanvas(world);
    frame.add(canvas);

    URL iconURL = this.getClass().getResource("/icon-sm.png");
    ImageIcon icon = new ImageIcon(Objects.requireNonNull(iconURL));
    frame.setIconImage(icon.getImage());

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
