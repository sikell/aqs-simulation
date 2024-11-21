package de.sikeller.aqs.visualization;

import de.sikeller.aqs.model.World;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;

public class TaxiScenarioCanvas extends JPanel {
  private final double height;
  private final double width;
  private final int canvasHeight;
  private final int canvasWidth;
  private final BufferedImage bufferedImage;

  public TaxiScenarioCanvas(World world) {
    this.height = world.getMaxY();
    this.width = world.getMaxX();
    this.canvasHeight = 800;
    this.canvasWidth = 800;
    setSize(canvasWidth, canvasHeight);
    this.bufferedImage = new BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_RGB);
  }

  public void update(World world) {
    SwingUtilities.invokeLater(
        () -> {
          Graphics2D g2d = bufferedImage.createGraphics();
          g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
          g2d.setColor(Color.WHITE);
          g2d.fillRect(0, 0, canvasWidth, canvasHeight);

          double widthRatio = canvasWidth / width;
          double heightRatio = canvasHeight / height;

          var taxis = TaxiDrawing.of(world.getTaxis());
          var clients = ClientDrawing.of(world.getClients());

          clients.forEach(t -> t.printBackgroundShape(g2d, widthRatio, heightRatio));
          taxis.forEach(t -> t.printBackgroundShape(g2d, widthRatio, heightRatio));

          taxis.forEach(t -> t.printForegroundShape(g2d, widthRatio, heightRatio));
          clients.forEach(t -> t.printForegroundShape(g2d, widthRatio, heightRatio));

          g2d.dispose();

          repaint();
        });
  }

  @Override
  public Dimension getPreferredSize() {
    return new Dimension(canvasWidth, canvasHeight);
  }

  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    g.drawImage(bufferedImage, 0, 0, null);
  }
}
