package de.sikeller.aqs.visualization;

import de.sikeller.aqs.model.SimulationControl;
import de.sikeller.aqs.model.SimulationObserver;
import de.sikeller.aqs.model.World;
import java.awt.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;

import de.sikeller.aqs.model.WorldObject;
import de.sikeller.aqs.visualization.controls.TaxiScenarioControl;
import de.sikeller.aqs.visualization.controls.VisualizationControl;
import de.sikeller.aqs.visualization.drawing.VisualizationProperties;
import de.sikeller.aqs.visualization.drawing.TaxiScenarioCanvas;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SimulationVisualization extends AbstractVisualization implements SimulationObserver {
  private static final int REPAINT_INTERVAL_MS = 50;
  private final TaxiScenarioCanvas canvas;

  /** null if waiting for next snapshot to be rendered, or a snapshot to be rendered next */
  private final AtomicReference<World> snapshot = new AtomicReference<>();

  public SimulationVisualization(World world, SimulationControl simulation) {
    super("Taxi Scenario Simulation");
    var controls = new JPanel();
    controls.setLayout(new BorderLayout());
    controls.add(new TaxiScenarioControl(simulation), BorderLayout.NORTH);
    var visuProperties = new VisualizationProperties();
    controls.add(new VisualizationControl(visuProperties), BorderLayout.SOUTH);
    canvas = new TaxiScenarioCanvas(world, visuProperties);

    frame.setLayout(new GridLayout(1, 2));
    frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
    frame.add(controls);
    frame.add(canvas);
    frame.setVisible(true);
    frame.pack();
  }

  @Override
  public void run() {
    Timer timer =
        new Timer(
            REPAINT_INTERVAL_MS,
            e -> {
              World world = snapshot.get();
              if (world == null) return;
              canvas.repaint(world);
              snapshot.set(null);
              if (!frame.isVisible()) {
                frame.setVisible(true);
              }
            });
    timer.start();
  }

  @Override
  public void onUpdate(WorldObject world) {
    // Only create a new snapshot if the last was rendered successfully (the reference is null) to
    // avoid unused snapshot calculations.
    snapshot.getAndUpdate(w -> w == null ? world.snapshot() : w);
  }
}
