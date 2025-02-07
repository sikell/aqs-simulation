package de.sikeller.aqs.visualization;

import static de.sikeller.aqs.visualization.VisualizationUtils.defaultFont;
import static java.lang.Math.round;

import de.sikeller.aqs.model.Client;
import java.awt.*;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

public class ClientDrawing extends EntityDrawing {
  private final int DEFAULT_MARKER_SIZE = 2;
  private final int markerSize;
  private final Client client;
  private final ClientDrawingProperties properties;

  public ClientDrawing(Client client, ClientDrawingProperties properties) {
    this.client = client;
    this.properties = properties;
    this.markerSize = DEFAULT_MARKER_SIZE * properties.getScale();
  }

  public interface ClientDrawingProperties extends DrawingProperties {
    boolean isShowClientPaths();

    boolean isShowClientNames();
  }

  public static List<ClientDrawing> of(
      Collection<Client> collection, ClientDrawingProperties properties) {
    return collection.stream()
        .sorted(Comparator.comparing(Client::getName))
        .map((Client c) -> new ClientDrawing(c, properties))
        .toList();
  }

  @Override
  public void printBackgroundShape(
      Graphics2D g, double canvasWidthRatio, double canvasHeightRatio) {
    if (properties.isShowClientPaths()) {
      printTarget(g, canvasWidthRatio, canvasHeightRatio);
    }
    if (properties.isShowClientNames()) {
      printName(g, canvasWidthRatio, canvasHeightRatio);
    }
  }

  @Override
  public void printForegroundShape(
      Graphics2D g, double canvasWidthRatio, double canvasHeightRatio) {
    printPosition(g, canvasWidthRatio, canvasHeightRatio);
  }

  private void printTarget(Graphics2D g, double canvasWidthRatio, double canvasHeightRatio) {
    g.setColor(Color.LIGHT_GRAY);
    g.fillOval(
        (int) round(client.getTarget().getX() * canvasWidthRatio) - markerSize / 3,
        (int) round(client.getTarget().getY() * canvasHeightRatio) - markerSize / 3,
        (int) round(markerSize / 1.5),
        (int) round(markerSize / 1.5));
    g.setStroke(
        new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0, new float[] {9}, 0));
    g.drawLine(
        (int) round(client.getPosition().getX() * canvasWidthRatio),
        (int) round(client.getPosition().getY() * canvasHeightRatio),
        (int) round(client.getTarget().getX() * canvasWidthRatio),
        (int) round(client.getTarget().getY() * canvasHeightRatio));
  }

  private void printPosition(Graphics g, double canvasWidthRatio, double canvasHeightRatio) {
    if (client.isFinished()) {
      g.setColor(Color.LIGHT_GRAY);
    } else {
      g.setColor(Color.BLUE);
    }
    g.fillOval(
        (int) round(client.getPosition().getX() * canvasWidthRatio) - markerSize / 2,
        (int) round(client.getPosition().getY() * canvasHeightRatio) - markerSize / 2,
        markerSize,
        markerSize);
  }

  private void printName(Graphics g, double canvasWidthRatio, double canvasHeightRatio) {
    if (client.isFinished()) {
      g.setColor(Color.LIGHT_GRAY);
    } else {
      g.setColor(Color.BLUE);
    }
    g.setFont(defaultFont());
    g.drawString(
        client.getName(),
        (int)
            round(
                client.getPosition().getX() * canvasWidthRatio
                    + ((double) DEFAULT_MARKER_SIZE / 2)),
        (int) round(client.getPosition().getY() * canvasHeightRatio + DEFAULT_MARKER_SIZE + 15));
  }
}
