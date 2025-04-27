package de.sikeller.aqs.taxi.algorithm.distributed.costing;

import de.sikeller.aqs.model.*;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

@Slf4j
public class SequentialCostCalculator implements CostCalculator {

  private Map<String, Integer> parameters = new HashMap<>();

  @Override
  public void setParameters(Map<String, Integer> parameters) {
    this.parameters = Objects.requireNonNullElseGet(parameters, HashMap::new);
  }

  @Override
  public CostCalculationResult calculateMarginalCost(
      Taxi taxi, Client newClient, double maxClientTripTime_s) {
    final long startTime = System.nanoTime();

    Position currentPos = taxi.getPosition();
    List<OrderNode> currentRouteNodes = taxi.getTargets().toList();
    // Passengers currently physically in the taxi (needed for capacity check start)
    Set<Client> initialPassengersInTaxi = new HashSet<>(taxi.getContainedPassengers());
    final double taxiSpeed_mps = taxi.getCurrentSpeed() / 3.6;

    if (taxiSpeed_mps <= 0) {
      log.error(
          "Taxi {} has invalid speed: {} m/s. Cannot calculate travel times.",
          taxi.getName(),
          taxiSpeed_mps);
      // Return infeasible immediately if speed is invalid
      return new CostCalculationResult(
          CostCalculationResult.INFEASIBLE_COST, System.nanoTime() - startTime, null);
    }

    double originalRouteLength_m = calculateRouteLengthNodes(currentPos, currentRouteNodes);
    double minNewRouteLength_m = CostCalculationResult.INFEASIBLE_COST;
    List<OrderNode> bestRoute = null;

    OrderNode newPickupNode = new OrderNode(newClient, newClient.getPosition());
    OrderNode newDropoffNode = new OrderNode(newClient, newClient.getTarget());

    // Iterate through all possible insertion positions for pickup
    for (int i = 0; i <= currentRouteNodes.size(); i++) {
      // Iterate through all possible insertion positions for dropoff (must be after pickup)
      // Note: If pickup is inserted at index i, dropoff can be inserted from i up to the new size.
      for (int j = i;
          j <= currentRouteNodes.size();
          j++) { // j represents index *after* potential pickup insertion
        List<OrderNode> candidateRoute = new LinkedList<>(currentRouteNodes);

        // Insert pickup at index i
        candidateRoute.add(i, newPickupNode);
        // Insert dropoff at index j (relative to the list *after* pickup insertion)
        // If j==i, dropoff comes right after pickup.
        candidateRoute.add(j + 1, newDropoffNode); // +1 because list size increased by 1

        // ToDo: Check if calculateRouteLengthNodes first is faster than checking validity
        // ToDo: Check if it's better to check the time limit for the new client first
        // ToDo: Check if it's more efficient to combine the following two validity checks into one
        // --- Validity Check 1: (Capacity) ---
        if (!isRouteValidByCapacity(
            currentPos, candidateRoute, taxi.getCapacity(), initialPassengersInTaxi)) {
          continue;
        }
        // --- Validity Check 2: (Time Limit) ---
        ClientTimes clientTimes =
            calculateClientTimesOnRoute(currentPos, candidateRoute, newClient, taxiSpeed_mps);
        if (clientTimes == null) continue;
        if (clientTimes.totalTime() > maxClientTripTime_s) {
          log.trace(
              "Taxi {}: Client {} exceeds max trip time. Time to pickup: {}, time in taxi: {}",
              taxi.getName(),
              newClient.getName(),
              clientTimes.timeToPickup,
              clientTimes.timeInTaxi);
          continue;
        }
        // --- Calculate Length ---
        double candidateLength_m = calculateRouteLengthNodes(currentPos, candidateRoute);
        // --- Update Minimum ---
        if (candidateLength_m < minNewRouteLength_m) {
          minNewRouteLength_m = candidateLength_m;
          bestRoute = candidateRoute;
        }
      }
    }

    final long calculationTimeNanos = System.nanoTime() - startTime;

    double marginalCost_m;
    if (minNewRouteLength_m == CostCalculationResult.INFEASIBLE_COST) {
      marginalCost_m = CostCalculationResult.INFEASIBLE_COST; // No valid insertion found
      log.trace(
          "Taxi {}: No feasible route found to insert client {}",
          taxi.getName(),
          newClient.getName());
    } else {
      marginalCost_m = Math.max(0, minNewRouteLength_m - originalRouteLength_m);
      log.trace(
          "Taxi {}: Best insertion for client {} has marginal cost: {}",
          taxi.getName(),
          newClient.getName(),
          marginalCost_m);
    }
    return new CostCalculationResult(marginalCost_m, calculationTimeNanos, bestRoute);
  }

  /**
   * Calculates the total length of a route defined by a starting position and a list of OrderNodes.
   *
   * @param startPos The starting position of the route (e.g., taxi's current position).
   * @param nodes The list of ordered stops (OrderNodes).
   * @return The total Euclidean distance of the route.
   */
  private double calculateRouteLengthNodes(Position startPos, List<OrderNode> nodes) {
    double totalLength = 0;
    Position lastPos = startPos;
    for (OrderNode node : nodes) {
      totalLength += lastPos.distance(node.getPosition());
      lastPos = node.getPosition();
    }
    return totalLength;
  }

  /**
   * Calculates the time in seconds until pickup and the time spent in the taxi for a specific
   * client along a given candidate route.
   *
   * @param startPos The starting position of the route (taxi's current position).
   * @param candidateNodes The proposed sequence of stops.
   * @param targetClient The client whose times are to be calculated.
   * @param taxiSpeed_mps The speed of the taxi in m/s.
   * @return A ClientTimes record containing timeToPickup and timeInTaxi, or null if pickup/dropoff
   *     not found correctly.
   */
  private ClientTimes calculateClientTimesOnRoute(
      Position startPos,
      List<OrderNode> candidateNodes,
      Client targetClient,
      double taxiSpeed_mps) {
    double distanceToPickup_m = -1.0; // Use -1 to indicate pickup not reached yet
    double distanceInTaxi_m = 0.0;
    Position lastPos = startPos;
    boolean pickedUp = false;
    double currentRouteDistance_m = 0.0; // Track total distance traveled up to current node

    if (taxiSpeed_mps <= 0) return null; // Cannot calculate time with invalid speed

    for (OrderNode node : candidateNodes) {
      double segmentDistance_m = lastPos.distance(node.getPosition());
      currentRouteDistance_m += segmentDistance_m;

      if (!pickedUp) {
        // Check if current node IS the pickup node
        if (node.getClient().equals(targetClient)
            && node.getPosition().equals(targetClient.getPosition())) {
          distanceToPickup_m = currentRouteDistance_m; // Total distance traveled until pickup point
          pickedUp = true;
        }
      } else { // Client is already picked up
        distanceInTaxi_m += segmentDistance_m; // Accumulate distance while client is in taxi

        // Check if current node IS the dropoff node
        if (node.getClient().equals(targetClient)
            && node.getPosition().equals(targetClient.getTarget())) {
          // Found dropoff, times can be calculated
          double timeToPickup_s = distanceToPickup_m / taxiSpeed_mps;
          double timeInTaxi_s = distanceInTaxi_m / taxiSpeed_mps;
          return new ClientTimes(timeToPickup_s, timeInTaxi_s);
        }
      }
      lastPos = node.getPosition();
    }

    log.warn(
        "Could not calculate client times for {} on route. Pickup found: {}. Dropoff not found?",
        targetClient.getName(),
        pickedUp);
    return null; // Should not happen if route contains both nodes for the client
  }

  /** Helper Record for client travel times */
  private record ClientTimes(double timeToPickup, double timeInTaxi) {
    public double totalTime() {
      return timeToPickup + timeInTaxi;
    }
  }

  /**
   * Checks if a candidate route is valid, primarily focusing on capacity constraints.
   *
   * @param startPos The starting position of the route.
   * @param candidateNodes The proposed sequence of stops.
   * @param capacity The maximum capacity of the taxi.
   * @param initialPassengersInTaxi The set of clients already in the taxi at the startPos.
   * @return True if the route respects capacity constraints, false otherwise.
   */
  private boolean isRouteValidByCapacity(
      Position startPos,
      List<OrderNode> candidateNodes,
      int capacity,
      Set<Client> initialPassengersInTaxi) {
    Set<Client> currentPassengers = new HashSet<>(initialPassengersInTaxi);
    Position lastPos = startPos; // Needed only if segment travel time/constraints were relevant

    // Check initial load, should not fail if taxi state is consistent
    if (currentPassengers.size() > capacity) {
      log.error(
          "Route invalid at start: initial load {} exceeds capacity {}",
          currentPassengers.size(),
          capacity);
      return false;
    }

    for (OrderNode node : candidateNodes) {
      // Simulate segment travel (if time constraints were added, check here)
      lastPos = node.getPosition(); // Update position for next segment start

      Client client = node.getClient();
      boolean isPickup = node.getPosition().equals(client.getPosition());
      boolean isDropoff = node.getPosition().equals(client.getTarget());

      // Handle dropoff *before* pickup if they occur at the same location
      if (isDropoff) {
        // Check if the client was actually in the set (should be if route is logical)
        if (!currentPassengers.remove(client)) {
          /* This might happen if the original route had this client planned but not picked up yet,
          and the new insertion places the dropoff before the pickup.
          For now, let's assume a valid simulation state where dropoff implies passenger was
          present.
          If issues arise, we need a more robust state tracking (planned vs. onboard). */
          log.warn(
              "Validity check: Client {} dropoff node encountered, but client not in current simulated passenger set.",
              client.getName());
        }
      }

      if (isPickup) {
        // Add passenger *after* checking potential dropoff at same location
        currentPassengers.add(client);
        // Check capacity *after* pickup
        if (currentPassengers.size() > capacity) {
          log.trace(
              "Route invalid: capacity {} exceeded after picking up {} at {}. Current passengers: {}",
              capacity,
              client.getName(),
              node.getPosition(),
              currentPassengers.size());
          return false;
        }
      }
    }
    return true;
  }
}
