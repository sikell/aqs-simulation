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
import javax.swing.border.EmptyBorder;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SimulationVisualization extends AbstractVisualization implements SimulationObserver, Runnable {
  private static final int REPAINT_INTERVAL_MS = 50;
  private final TaxiScenarioCanvas canvas;

  /** null if waiting for next snapshot to be rendered, or a snapshot to be rendered next */
  private final AtomicReference<World> snapshot = new AtomicReference<>();

  public SimulationVisualization(World world, SimulationControl simulation) {
    super("Taxi Scenario Simulation");
    TaxiScenarioControl taxiScenarioControl = new TaxiScenarioControl(simulation);

    var controls = new JPanel();
    controls.setLayout(new BorderLayout(0, 12));
    controls.setBorder(new EmptyBorder(8, 8, 8, 8));
    controls.add(taxiScenarioControl, BorderLayout.NORTH);
    var visuProperties = new VisualizationProperties();
    taxiScenarioControl.setVisualizationProperties(visuProperties);
    VisualizationControl visualizationControl = new VisualizationControl(visuProperties);
    taxiScenarioControl.setP2PModeUiListener(visualizationControl::setP2PModeUiState);
    controls.add(visualizationControl, BorderLayout.SOUTH);
    canvas = new TaxiScenarioCanvas(world, visuProperties);

    var simulationArea = new JPanel(new BorderLayout(0, 14));
    simulationArea.setBorder(new EmptyBorder(8, 8, 8, 8));
    simulationArea.add(canvas, BorderLayout.CENTER);
    simulationArea.add(taxiScenarioControl.getP2PTopologyComponent(), BorderLayout.SOUTH);

    JScrollPane controlsScrollPane = new JScrollPane(controls);
    controlsScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    controlsScrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);

    var content = new JPanel(new GridLayout(1, 2, 14, 0));
    content.setBorder(new EmptyBorder(8, 8, 8, 8));
    content.add(controlsScrollPane);
    content.add(simulationArea);
    content.setPreferredSize(new Dimension(1450, 980));

    var scrollPane = new JScrollPane(content);
    scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
    scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);

    frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
    frame.setContentPane(scrollPane);
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
  public void onUpdate(WorldObject world, boolean forceUpdate) {
    // Only create a new snapshot if the last was rendered successfully (the reference is null) to
    // avoid unused snapshot calculations.
    snapshot.getAndUpdate(w -> w == null || forceUpdate ? world.snapshot() : w);
  }
}
