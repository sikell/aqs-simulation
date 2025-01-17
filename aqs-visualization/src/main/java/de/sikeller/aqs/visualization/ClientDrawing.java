package de.sikeller.aqs.visualization;

import static java.lang.Math.round;

import de.sikeller.aqs.model.Client;
import java.awt.*;
import java.util.Collection;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ClientDrawing extends EntityDrawing {
  private final int CLIENT_MARKER_SIZE = 10;
  private final Client client;
  private final ClientDrawingProperties properties;

  public interface ClientDrawingProperties {
    boolean isShowClientPaths();

    boolean isShowClientNames();
  }

  public static Collection<ClientDrawing> of(
      Collection<Client> collection, ClientDrawingProperties properties) {
    return collection.stream()
        .map((Client c) -> new ClientDrawing(c, properties))
        .collect(Collectors.toSet());
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
        (int) round(client.getTarget().getX() * canvasWidthRatio) - CLIENT_MARKER_SIZE / 3,
        (int) round(client.getTarget().getY() * canvasHeightRatio) - CLIENT_MARKER_SIZE / 3,
        (int) round(CLIENT_MARKER_SIZE / 1.5),
        (int) round(CLIENT_MARKER_SIZE / 1.5));
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
        (int) round(client.getPosition().getX() * canvasWidthRatio) - CLIENT_MARKER_SIZE / 2,
        (int) round(client.getPosition().getY() * canvasHeightRatio) - CLIENT_MARKER_SIZE / 2,
        CLIENT_MARKER_SIZE,
        CLIENT_MARKER_SIZE);
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
                client.getPosition().getX() * canvasWidthRatio + ((double) CLIENT_MARKER_SIZE / 2)),
        (int) round(client.getPosition().getY() * canvasHeightRatio + CLIENT_MARKER_SIZE + 15));
  }
}
