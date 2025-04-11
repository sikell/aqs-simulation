package de.sikeller.aqs.taxi.algorithm.TaxiAlgorithmDistributed.rqs;

import de.sikeller.aqs.model.*;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

@Slf4j
public class SimulatedRangeQuerySystem implements RangeQuerySystem {

  private Map<String, Integer> parameters = new HashMap<>();

  @Override
  public void setParameters(Map<String, Integer> parameters) {
    this.parameters = Objects.requireNonNullElseGet(parameters, HashMap::new);
  }

  @Override
  public Set<Taxi> findTaxisInRange(
      World world, Position clientStart, Position clientTarget, double searchRadius) {
    Set<Taxi> candidateTaxis = new HashSet<>();
    List<Position> clientRouteSegment = List.of(clientStart, clientTarget); // Simple direct route
    final boolean calculateFullTaxis = parameters.getOrDefault("CalculateFullTaxis", 0) != 0;

    for (Taxi taxi : world.getTaxis()) {
      if (!calculateFullTaxis && !taxi.hasCapacity()) {
        log.trace("RQS - Taxi {} skipped (no capacity)", taxi.getName());
        continue;
      }
      if (taxi.isMoving()) {
        // Taxi is active and has a route
        List<OrderNode> taxiRouteNodes = taxi.getTargets().toList();
        if (!taxiRouteNodes.isEmpty()) {
          // Combine current position with target positions to form route segments
          List<Position> taxiRoutePoints = new java.util.LinkedList<>();
          taxiRoutePoints.add(taxi.getPosition()); // Start from current position
          taxiRoutePoints.addAll(taxiRouteNodes.stream().map(OrderNode::getPosition).toList());

          // --- SIMPLIFIED CHECK ---
          // Check distance from client start to any point on the taxi's route polyline.
          // This is an approximation of route overlap/proximity.
          // A more complex check would involve segment-to-segment distance.
          if (isClientCloseToTaxiRoute(clientStart, taxiRoutePoints, searchRadius)) {
            candidateTaxis.add(taxi);
            log.trace(
                "Active Taxi {} added as candidate for client at {} (route proximity check)",
                taxi.getName(),
                clientStart);
          }
        } else {
          // Taxi is marked as moving but has no targets? Might be an edge case or just finished.
          // Treat as idle for safety? Or ignore? Let's check distance to current position like
          // idle.
          if (taxi.getPosition().distance(clientStart) <= searchRadius) {
            candidateTaxis.add(taxi);
            log.trace(
                "Idle/Empty-Route Taxi {} added as candidate for client at {} (position proximity check)",
                taxi.getName(),
                clientStart);
          }
        }

      } else {
        // Taxi is idle (not moving)
        // Check distance from client start to taxi's current position
        // TODO: Implement the concept of idle taxis expanding their area later.
        // For now, just check simple distance.
        if (taxi.getPosition().distance(clientStart) <= searchRadius) {
          candidateTaxis.add(taxi);
          log.trace(
              "Idle Taxi {} added as candidate for client at {} (position proximity check)",
              taxi.getName(),
              clientStart);
        }
      }
    }

    return candidateTaxis;
  }

  /**
   * Simplified check: Calculates the minimum distance from a point to a polyline (sequence of
   * points).
   *
   * @param point The client's starting point.
   * @param polylinePoints The list of points defining the taxi's route (including current
   *     position).
   * @param radius The search radius.
   * @return True if the minimum distance to the point is within radius.
   */
  private boolean isClientCloseToTaxiRoute(
      Position point, List<Position> polylinePoints, double radius) {
    if (polylinePoints == null || polylinePoints.size() < 2) {
      // Need at least 2 points (current pos + 1 target) to form a segment
      if (polylinePoints != null && polylinePoints.size() == 1) {
        // Only current position available, check distance to that point
        return point.distance(polylinePoints.getFirst()) <= radius;
      }
      return false; // Not a valid polyline
    }

    double minDistanceSq = Double.POSITIVE_INFINITY;

    // Iterate through each segment of the polyline
    for (int i = 0; i < polylinePoints.size() - 1; i++) {
      Position p1 = polylinePoints.get(i);
      Position p2 = polylinePoints.get(i + 1);
      minDistanceSq = Math.min(minDistanceSq, pointToSegmentDistanceSq(point, p1, p2));
    }

    // Check if the minimum distance is within the radius
    return minDistanceSq <= radius * radius;
  }

  /**
   * Calculates the squared distance from a point to a line segment. Using squared distance avoids
   * costly square root operations until the final comparison.
   *
   * @param p The point.
   * @param a Start point of the segment.
   * @param b End point of the segment.
   * @return The squared perpendicular distance, or squared distance to the nearest endpoint.
   */
  private double pointToSegmentDistanceSq(Position p, Position a, Position b) {
    double l2 = distanceSq(a, b); // Squared length of the segment
    if (l2 == 0.0) return distanceSq(p, a); // Segment is a point

    // Consider the line extending the segment, parameterized as a + t (b - a).
    // We find projection of point p onto the line.
    // It falls where t = [(p-a) . (b-a)] / |b-a|^2
    double dotProduct =
        (double)
            ((p.getX() - a.getX()) * (b.getX() - a.getX())
                + (p.getY() - a.getY()) * (b.getY() - a.getY()));
    double t = Math.max(0, Math.min(1, dotProduct / l2)); // Clamp t to [0, 1] for the segment

    // Projection falls on the segment
    Position projection =
        new Position(
            (int) Math.round(a.getX() + t * (b.getX() - a.getX())),
            (int) Math.round(a.getY() + t * (b.getY() - a.getY())));

    return distanceSq(p, projection); // Squared distance from p to projection
  }

  /**
   * Calculates the squared Euclidean distance between two points.
   *
   * @param p1 First position.
   * @param p2 Second position.
   * @return Squared distance.
   */
  private double distanceSq(Position p1, Position p2) {
    double dx = p1.getX() - p2.getX();
    double dy = p1.getY() - p2.getY();
    return dx * dx + dy * dy;
  }
}
