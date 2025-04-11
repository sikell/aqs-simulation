package de.sikeller.aqs.taxi.algorithm.TaxiAlgorithmDistributed.costing;

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
  public CostCalculationResult calculateMarginalCost(Taxi taxi, Client newClient) {
    long startTime = System.nanoTime();

    Position currentPos = taxi.getPosition();
    List<OrderNode> currentRouteNodes = taxi.getTargets().toList();
    int capacity = taxi.getCapacity();
    // Passengers currently physically in the taxi (needed for capacity check start)
    Set<Client> initialPassengersInTaxi = new HashSet<>(taxi.getContainedPassengers());

    double originalRouteLength = calculateRouteLengthNodes(currentPos, currentRouteNodes);
    double minNewRouteLength = CostCalculationResult.INFEASIBLE_COST;

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
        // --- Validity Check (Capacity) ---
        if (!isRouteValid(currentPos, candidateRoute, capacity, initialPassengersInTaxi)) {
          // If not valid, just continue to the next insertion possibility
          continue;
        }
        // --- Calculate Length ---
        double candidateLength = calculateRouteLengthNodes(currentPos, candidateRoute);
        // --- Update Minimum ---
        if (candidateLength < minNewRouteLength) {
          minNewRouteLength = candidateLength;
        }
      }
    }

    long endTime = System.nanoTime();
    long calculationTimeNanos = endTime - startTime;

    double marginalCost;
    if (minNewRouteLength == CostCalculationResult.INFEASIBLE_COST) {
      marginalCost = CostCalculationResult.INFEASIBLE_COST; // No valid insertion found
      log.trace(
          "Taxi {}: No feasible route found to insert client {}",
          taxi.getName(),
          newClient.getName());
    } else {
      marginalCost = Math.max(0, minNewRouteLength - originalRouteLength);
      log.trace(
          "Taxi {}: Best insertion for client {} has marginal cost: {}",
          taxi.getName(),
          newClient.getName(),
          marginalCost);
    }
    return new CostCalculationResult(marginalCost, calculationTimeNanos);
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
   * Checks if a candidate route is valid, primarily focusing on capacity constraints.
   *
   * @param startPos The starting position of the route.
   * @param candidateNodes The proposed sequence of stops.
   * @param capacity The maximum capacity of the taxi.
   * @param initialPassengersInTaxi The set of clients already in the taxi at the startPos.
   * @return True if the route respects capacity constraints, false otherwise.
   */
  private boolean isRouteValid(
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
