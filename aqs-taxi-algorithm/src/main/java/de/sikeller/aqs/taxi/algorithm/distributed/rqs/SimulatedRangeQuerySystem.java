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
  private volatile World cachedWorld;
  private volatile long cachedTick = Long.MIN_VALUE;
  private volatile List<Taxi> cachedTaxisSnapshot = List.of();

  @Override
  public void setParameters(Map<String, Integer> parameters) {
    this.parameters = Objects.requireNonNullElseGet(parameters, HashMap::new);
  }

  @Override
  public Set<Taxi> findTaxisInRange(
      World world, Position clientStart, Position clientTarget, double searchRadius) {
    Set<Taxi> candidateTaxis = new HashSet<>();
    if (world == null || clientStart == null) {
      return candidateTaxis;
    }
    final boolean calculateFullTaxis = parameters.getOrDefault("CalculateFullTaxis", 0) != 0;
    final boolean useRouteProximity =
        parameters.getOrDefault(RQS_ROUTE_PROXIMITY_MODE_PARAMETER, 0) != 0;
    List<Taxi> taxisSnapshot = taxisSnapshotForTick(world);

    double radiusSq = searchRadius * searchRadius;

    for (Taxi taxi : taxisSnapshot) {
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

      if (distanceSq(taxi.getPosition(), clientStart) <= radiusSq) {
        candidateTaxis.add(taxi);
      }
    }

    return candidateTaxis;
  }

  private synchronized List<Taxi> taxisSnapshotForTick(World world) {
    if (world == null) {
      return List.of();
    }
    long tick = world.getCurrentTime();
    if (world == cachedWorld && tick == cachedTick) {
      return cachedTaxisSnapshot;
    }
    cachedWorld = world;
    cachedTick = tick;
    cachedTaxisSnapshot = new ArrayList<>(world.getTaxis());
    return cachedTaxisSnapshot;
  }


  private boolean isTaxiRouteInRange(Taxi taxi, Position clientStart, double searchRadius) {
    List<OrderNode> taxiRouteNodes = taxi.getTargets().toList();
    double radiusSq = searchRadius * searchRadius;
    if (taxiRouteNodes.isEmpty()) {
      return distanceSq(taxi.getPosition(), clientStart) <= radiusSq;
    }

    Position previous = taxi.getPosition();
    for (OrderNode node : taxiRouteNodes) {
      Position next = node == null ? null : node.getPosition();
      if (previous != null && next != null) {
        if (pointToSegmentDistanceSq(clientStart, previous, next) <= radiusSq) {
          return true;
        }
      }
      if (next != null) {
        previous = next;
      }
    }
    return false;
  }

  private double pointToSegmentDistanceSq(Position p, Position a, Position b) {
    double l2 = distanceSq(a, b);
    if (l2 == 0.0) {
      return distanceSq(p, a);
    }

    double dotProduct =
        (p.getX() - a.getX()) * (b.getX() - a.getX())
            + (p.getY() - a.getY()) * (b.getY() - a.getY());
    double t = Math.clamp(dotProduct / l2, 0.0, 1.0);

    double projectionX = a.getX() + t * (b.getX() - a.getX());
    double projectionY = a.getY() + t * (b.getY() - a.getY());
    double dx = p.getX() - projectionX;
    double dy = p.getY() - projectionY;
    return dx * dx + dy * dy;
  }

  private double distanceSq(Position p1, Position p2) {
    double dx = p1.getX() - p2.getX();
    double dy = p1.getY() - p2.getY();
    return dx * dx + dy * dy;
  }
}
