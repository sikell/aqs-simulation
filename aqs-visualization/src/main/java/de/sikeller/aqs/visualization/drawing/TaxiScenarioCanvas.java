package de.sikeller.aqs.visualization.drawing;

import static de.sikeller.aqs.visualization.drawing.VisualizationUtils.successColor;
import static de.sikeller.aqs.visualization.drawing.VisualizationUtils.taxiColor;
import static de.sikeller.aqs.visualization.drawing.VisualizationUtils.todoColor;

import de.sikeller.aqs.model.Client;
import de.sikeller.aqs.model.P2PNetworkEdgeSnapshot;
import de.sikeller.aqs.model.P2PNetworkNodeSnapshot;
import de.sikeller.aqs.model.P2PNetworkSnapshot;
import de.sikeller.aqs.model.Taxi;
import de.sikeller.aqs.model.World;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import javax.swing.JProgressBar;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TaxiScenarioCanvas extends JPanel {
  private double height;
  private double width;
  private final VisualizationProperties visuProperties;
  private final int canvasHeight;
  private final int canvasWidth;
  private final int scale = 4;
  private final BufferedImage bufferedImage;
  private final JProgressBar spawnProgressBar;
  private final JProgressBar finishedProgressBar;

  public TaxiScenarioCanvas(World world, VisualizationProperties visuProperties) {
    this.height = world.getSize().getMaxY();
    this.width = world.getSize().getMaxX();
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
    this.width = world.getSize().getMaxX();
    this.height = world.getSize().getMaxY();
    Graphics2D g2d = bufferedImage.createGraphics();
    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    g2d.scale(scale, scale);

    g2d.setColor(Color.WHITE);
    g2d.fillRect(0, 0, canvasWidth, canvasHeight);

    double widthRatio = canvasWidth / width;
    double heightRatio = canvasHeight / height;

    var background = new BackgroundDrawing(visuProperties, world.getCurrentTime());
    background.printBackgroundShape(g2d, widthRatio, heightRatio, canvasWidth, canvasHeight);

    var taxis = TaxiDrawing.of(world.getTaxis(), visuProperties);
    var clients = ClientDrawing.of(world.getSpawnedClients(), visuProperties);

    if (visuProperties.isShowRqsRecognitionRange() && visuProperties.getRqsRecognitionRadius() > 0) {
      drawRqsRecognitionOverlay(g2d, world, widthRatio, heightRatio);
    }
    if (visuProperties.isShowTaxiTopologyLinks()) {
      drawTaxiTopologyOverlay(g2d, world, widthRatio, heightRatio);
    }

    clients.forEach(t -> t.printBackgroundShape(g2d, widthRatio, heightRatio));
    taxis.forEach(t -> t.printBackgroundShape(g2d, widthRatio, heightRatio));

    clients.forEach(t -> t.printForegroundShape(g2d, widthRatio, heightRatio));
    taxis.forEach(t -> t.printForegroundShape(g2d, widthRatio, heightRatio));

    if (visuProperties.isShowPageRankHq()) {
      drawPageRankHqMarkers(g2d, widthRatio, heightRatio);
    }

    g2d.dispose();

    spawnProgressBar.setValue(world.getSpawnProgress());
    finishedProgressBar.setValue(world.getFinishedProgress());
    SwingUtilities.invokeLater(this::repaint);
  }

  private void drawPageRankHqMarkers(
      Graphics2D g2d, double widthRatio, double heightRatio) {
    Map<String, int[]> hqPositions = visuProperties.getTaxiPageRankHqPositions();
    if (hqPositions == null || hqPositions.isEmpty()) {
      return;
    }
    Stroke previous = g2d.getStroke();
    g2d.setStroke(new BasicStroke(1.5f));
    int half = 6;
    for (Map.Entry<String, int[]> entry : hqPositions.entrySet()) {
      int[] pos = entry.getValue();
      if (pos == null || pos.length < 2) continue;
      int cx = (int) Math.round(pos[0] * widthRatio);
      int cy = (int) Math.round(pos[1] * heightRatio);
      if (!isWithinCanvas(cx, cy)) continue;
      // upward-pointing triangle: tip at top
      int[] xs = {cx, cx - half, cx + half};
      int[] ys = {cy - half, cy + half, cy + half};
      Color taxiColor = taxiColor(entry.getKey());
      g2d.setColor(new Color(taxiColor.getRed(), taxiColor.getGreen(), taxiColor.getBlue(), 200));
      g2d.fillPolygon(xs, ys, 3);
      g2d.setColor(taxiColor.darker());
      g2d.drawPolygon(xs, ys, 3);
    }
    g2d.setStroke(previous);
  }

  private void drawRqsRecognitionOverlay(
      Graphics2D g2d, World world, double widthRatio, double heightRatio) {
    double worldToPixel = Math.min(widthRatio, heightRatio);
    int radiusPx = Math.max(3, (int) Math.round(visuProperties.getRqsRecognitionRadius() * worldToPixel));

    Stroke oldStroke = g2d.getStroke();
    Stroke dashedStroke =
        new BasicStroke(
            1.0f,
            BasicStroke.CAP_ROUND,
            BasicStroke.JOIN_ROUND,
            10f,
            new float[] {5f, 4f},
            0f);

    for (Taxi taxi : world.getTaxis()) {
      int tx = (int) Math.round(taxi.getPosition().getX() * widthRatio);
      int ty = (int) Math.round(taxi.getPosition().getY() * heightRatio);
      if (!isWithinCanvas(tx, ty)) {
        continue;
      }

      g2d.setStroke(dashedStroke);
      g2d.setColor(new Color(95, 68, 210, 110));
      g2d.drawOval(tx - radiusPx, ty - radiusPx, radiusPx * 2, radiusPx * 2);

      for (Client client : world.getSpawnedClients()) {
        if (!isRenderableClientForOverlay(client)) {
          continue;
        }
        if (taxi.getPosition().distance(client.getPosition()) > visuProperties.getRqsRecognitionRadius()) {
          continue;
        }
        int cx = (int) Math.round(client.getPosition().getX() * widthRatio);
        int cy = (int) Math.round(client.getPosition().getY() * heightRatio);
        if (!isWithinCanvas(cx, cy)) {
          continue;
        }
        g2d.setColor(new Color(132, 94, 255, 70));
        g2d.drawLine(tx, ty, cx, cy);
      }
    }

    g2d.setStroke(oldStroke);
  }

  // Keep overlay semantics aligned with client rendering to avoid lines to invisible clients.
  private boolean isRenderableClientForOverlay(Client client) {
    return client != null && (!client.isFinished() || visuProperties.isShowFinishedClients());
  }

  private boolean isWithinCanvas(int x, int y) {
    return x >= 0 && y >= 0 && x <= canvasWidth && y <= canvasHeight;
  }

  private void drawTaxiTopologyOverlay(
      Graphics2D g2d, World world, double widthRatio, double heightRatio) {
    P2PNetworkSnapshot snapshot = visuProperties.getP2pNetworkSnapshot();
    if (snapshot == null || snapshot.nodes().isEmpty() || snapshot.edges().isEmpty()) {
      return;
    }

    Map<String, Taxi> taxiByNodeId = new HashMap<>();
    for (Taxi taxi : world.getTaxis()) {
      taxiByNodeId.put(taxi.getName(), taxi);
      taxiByNodeId.put("vehicle-" + taxi.getName(), taxi);
    }

    Map<String, String> roleByNodeId = new HashMap<>();
    for (P2PNetworkNodeSnapshot node : snapshot.nodes()) {
      roleByNodeId.put(node.id(), node.role() == null ? "" : node.role().trim().toUpperCase());
    }

    Stroke previous = g2d.getStroke();
    Stroke defaultEdgeStroke = new BasicStroke(1.4f);
    Stroke shortcutEdgeStroke =
        new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, new float[] {6f, 4f}, 0f);

    for (P2PNetworkEdgeSnapshot edge : snapshot.edges()) {
      if (!"VEHICLE".equals(roleByNodeId.getOrDefault(edge.fromNodeId(), ""))
          || !"VEHICLE".equals(roleByNodeId.getOrDefault(edge.toNodeId(), ""))) {
        continue;
      }

      Taxi fromTaxi = taxiByNodeId.get(edge.fromNodeId());
      Taxi toTaxi = taxiByNodeId.get(edge.toNodeId());
      if (fromTaxi == null || toTaxi == null) {
        continue;
      }

      int x1 = (int) Math.round(fromTaxi.getPosition().getX() * widthRatio);
      int y1 = (int) Math.round(fromTaxi.getPosition().getY() * heightRatio);
      int x2 = (int) Math.round(toTaxi.getPosition().getX() * widthRatio);
      int y2 = (int) Math.round(toTaxi.getPosition().getY() * heightRatio);
      if (edge.shortcut()) {
        g2d.setStroke(shortcutEdgeStroke);
        g2d.setColor(new Color(0, 220, 120, 255));
      } else {
        g2d.setStroke(defaultEdgeStroke);
        g2d.setColor(new Color(255, 140, 0, 130));
      }
      g2d.drawLine(x1, y1, x2, y2);
    }

    g2d.setStroke(previous);
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
