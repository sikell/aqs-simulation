package de.sikeller.aqs.taxi.algorithm.distributed.rqs;

import de.sikeller.aqs.model.OrderNode;
import de.sikeller.aqs.model.Position;
import de.sikeller.aqs.model.Taxi;
import de.sikeller.aqs.model.World;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SimulatedRangeQuerySystem implements RangeQuerySystem {

  private static final String RQS_ROUTE_PROXIMITY_MODE_PARAMETER = "p2pRqsRouteProximityMode";

  private Map<String, Integer> parameters = new HashMap<>();

  @Override
  public void setParameters(Map<String, Integer> parameters) {
    this.parameters = Objects.requireNonNullElseGet(parameters, HashMap::new);
  }

  @Override
  public Set<Taxi> findTaxisInRange(
      World world, Position clientStart, Position clientTarget, double searchRadius) {
    Set<Taxi> candidateTaxis = new HashSet<>();
    final boolean calculateFullTaxis = parameters.getOrDefault("CalculateFullTaxis", 0) != 0;
    final boolean useRouteProximity = parameters.getOrDefault(RQS_ROUTE_PROXIMITY_MODE_PARAMETER, 0) != 0;

    for (Taxi taxi : world.getTaxis()) {
      if (!calculateFullTaxis && !taxi.hasCapacity()) {
        log.trace("RQS - Taxi {} skipped (no capacity)", taxi.getName());
        continue;
      }

      if (useRouteProximity && taxi.isMoving()) {
        if (isTaxiRouteInRange(taxi, clientStart, searchRadius)) {
          candidateTaxis.add(taxi);
        }
        continue;
      }

      if (taxi.getPosition().distance(clientStart) <= searchRadius) {
        candidateTaxis.add(taxi);
      }
    }

    return candidateTaxis;
  }

  private boolean isTaxiRouteInRange(Taxi taxi, Position clientStart, double searchRadius) {
    List<OrderNode> taxiRouteNodes = taxi.getTargets().toList();
    if (taxiRouteNodes.isEmpty()) {
      return taxi.getPosition().distance(clientStart) <= searchRadius;
    }

    List<Position> routePoints = new ArrayList<>();
    routePoints.add(taxi.getPosition());
    routePoints.addAll(taxiRouteNodes.stream().map(OrderNode::getPosition).toList());
    return isPointCloseToPolyline(clientStart, routePoints, searchRadius);
  }

  private boolean isPointCloseToPolyline(Position point, List<Position> polylinePoints, double radius) {
    if (polylinePoints == null || polylinePoints.size() < 2) {
      if (polylinePoints != null && polylinePoints.size() == 1) {
        return point.distance(polylinePoints.getFirst()) <= radius;
      }
      return false;
    }

    double minDistanceSq = Double.POSITIVE_INFINITY;
    for (int i = 0; i < polylinePoints.size() - 1; i++) {
      Position p1 = polylinePoints.get(i);
      Position p2 = polylinePoints.get(i + 1);
      minDistanceSq = Math.min(minDistanceSq, pointToSegmentDistanceSq(point, p1, p2));
    }
    return minDistanceSq <= radius * radius;
  }

  private double pointToSegmentDistanceSq(Position p, Position a, Position b) {
    double l2 = distanceSq(a, b);
    if (l2 == 0.0) {
      return distanceSq(p, a);
    }

    double dotProduct =
        (double)
            ((p.getX() - a.getX()) * (b.getX() - a.getX())
                + (p.getY() - a.getY()) * (b.getY() - a.getY()));
    double t = Math.max(0, Math.min(1, dotProduct / l2));

    Position projection =
        new Position(
            (int) Math.round(a.getX() + t * (b.getX() - a.getX())),
            (int) Math.round(a.getY() + t * (b.getY() - a.getY())));

    return distanceSq(p, projection);
  }

  private double distanceSq(Position p1, Position p2) {
    double dx = p1.getX() - p2.getX();
    double dy = p1.getY() - p2.getY();
    return dx * dx + dy * dy;
  }
}
