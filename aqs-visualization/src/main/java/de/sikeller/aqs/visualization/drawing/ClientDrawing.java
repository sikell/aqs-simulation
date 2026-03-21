package de.sikeller.aqs.visualization.drawing;

import static de.sikeller.aqs.visualization.drawing.VisualizationUtils.defaultFont;
import static de.sikeller.aqs.visualization.drawing.VisualizationUtils.taxiColor;
import static java.lang.Math.round;

import de.sikeller.aqs.model.Client;

import java.awt.*;
import java.awt.geom.Arc2D;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

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

    boolean isShowClientPositions();

    boolean isShowClientNames();

    boolean isShowFinishedClients();

    boolean isShowClientKnowledgeColors();

    Map<String, List<String>> getTaxiKnownClientIds();
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
    if (client.isFinished() && !properties.isShowFinishedClients()) {
      return;
    }
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
    if (client.isFinished() && !properties.isShowFinishedClients()) {
      return;
    }
    if (properties.isShowClientPositions()) {
      printPosition(g, canvasWidthRatio, canvasHeightRatio);
    }
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

  private void printPosition(Graphics2D g, double canvasWidthRatio, double canvasHeightRatio) {
    int x = (int) round(client.getPosition().getX() * canvasWidthRatio) - markerSize / 2;
    int y = (int) round(client.getPosition().getY() * canvasHeightRatio) - markerSize / 2;
    if (client.isFinished()) {
      g.setColor(Color.LIGHT_GRAY);
      g.fillOval(x, y, markerSize, markerSize);
      return;
    }

    if (!properties.isShowClientKnowledgeColors()) {
      g.setColor(Color.BLUE);
      g.fillOval(x, y, markerSize, markerSize);
      return;
    }

    List<String> knownTaxiIds =
        properties.getTaxiKnownClientIds().entrySet().stream()
            .filter(entry -> entry.getValue() != null && entry.getValue().contains(client.getName()))
            .map(Map.Entry::getKey)
            .filter(id -> id != null && !id.isBlank())
            .distinct()
            .sorted()
            .toList();
    if (knownTaxiIds.isEmpty()) {
      g.setColor(Color.BLUE);
      g.fillOval(x, y, markerSize, markerSize);
      return;
    }

    if (knownTaxiIds.size() == 1) {
      g.setColor(taxiColor(knownTaxiIds.getFirst()));
      g.fillOval(x, y, markerSize, markerSize);
      return;
    }

    // Render each taxi as a radial pie segment so multi-knowledge remains visible per client.
    double segmentAngle = 360.0 / knownTaxiIds.size();
    double startAngle = 90.0;
    for (String taxiId : knownTaxiIds) {
      g.setColor(taxiColor(taxiId));
      g.fill(new Arc2D.Double(x, y, markerSize, markerSize, startAngle, -segmentAngle, Arc2D.PIE));
      startAngle -= segmentAngle;
    }
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
