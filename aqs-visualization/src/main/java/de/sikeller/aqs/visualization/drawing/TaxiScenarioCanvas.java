package de.sikeller.aqs.visualization.drawing;

import static de.sikeller.aqs.visualization.drawing.VisualizationUtils.successColor;
import static de.sikeller.aqs.visualization.drawing.VisualizationUtils.todoColor;

import de.sikeller.aqs.model.World;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.swing.*;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TaxiScenarioCanvas extends JPanel {
  private final double height;
  private final double width;
  private final VisualizationProperties visuProperties;
  private final int canvasHeight;
  private final int canvasWidth;
  private final int scale = 4;
  private final BufferedImage bufferedImage;
  private final JProgressBar spawnProgressBar;
  private final JProgressBar finishedProgressBar;

  public TaxiScenarioCanvas(World world, VisualizationProperties visuProperties) {
    this.height = world.getMaxY();
    this.width = world.getMaxX();
    this.visuProperties = visuProperties;
    this.canvasHeight = 700;
    this.canvasWidth = 700;
    setSize(canvasWidth, canvasHeight);
    this.bufferedImage =
        new BufferedImage(canvasWidth * scale, canvasHeight * scale, BufferedImage.TYPE_INT_RGB);

    spawnProgressBar = createProgressBar(todoColor());
    finishedProgressBar = createProgressBar(successColor());

    clear();
  }

  private JProgressBar createProgressBar(Color color) {
    final JProgressBar progressBar;
    progressBar = new JProgressBar(0, 100);
    progressBar.setValue(0);
    progressBar.setForeground(color);
    progressBar.setMaximum(100);
    progressBar.setStringPainted(true);
    progressBar.setPreferredSize(new Dimension(300, 10));
    add(progressBar);
    return progressBar;
  }

  public void clear() {
    Graphics2D g2d = bufferedImage.createGraphics();
    g2d.scale(scale, scale);
    g2d.setColor(Color.WHITE);
    g2d.fillRect(0, 0, canvasWidth, canvasHeight);
    g2d.dispose();
    spawnProgressBar.setValue(0);
    finishedProgressBar.setValue(0);
    SwingUtilities.invokeLater(this::repaint);
  }

  public void repaint(World world) {
    Graphics2D g2d = bufferedImage.createGraphics();
    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    g2d.setRenderingHint(
        RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    g2d.scale(scale, scale);

    g2d.setColor(Color.WHITE);
    g2d.fillRect(0, 0, canvasWidth, canvasHeight);

    double widthRatio = canvasWidth / width;
    double heightRatio = canvasHeight / height;

    var background = new BackgroundDrawing(visuProperties, world.getCurrentTime());
    background.printBackgroundShape(g2d, widthRatio, heightRatio, canvasWidth, canvasHeight);

    var taxis = TaxiDrawing.of(world.getTaxis(), visuProperties);
    var clients = ClientDrawing.of(world.getSpawnedClients(), visuProperties);

    clients.forEach(t -> t.printBackgroundShape(g2d, widthRatio, heightRatio));
    taxis.forEach(t -> t.printBackgroundShape(g2d, widthRatio, heightRatio));

    clients.forEach(t -> t.printForegroundShape(g2d, widthRatio, heightRatio));
    taxis.forEach(t -> t.printForegroundShape(g2d, widthRatio, heightRatio));
    g2d.dispose();

    spawnProgressBar.setValue(world.getSpawnProgress());
    finishedProgressBar.setValue(world.getFinishedProgress());
    SwingUtilities.invokeLater(this::repaint);
  }

  @Override
  public Dimension getPreferredSize() {
    return new Dimension(canvasWidth, canvasHeight);
  }

  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    Graphics2D g2draw = (Graphics2D) g.create();
    g2draw.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g2draw.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    g2draw.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    g2draw.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    try {
      g2draw.scale(1.0 / scale, 1.0 / scale);
      g2draw.drawImage(bufferedImage, 0, 0, null);
    } finally {
      g2draw.dispose();
    }
  }
}
