package de.sikeller.aqs.taxi.algorithm;

import de.sikeller.aqs.model.*;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static de.sikeller.aqs.model.ClientMode.WAITING;

@Slf4j
@Getter
public class TaxiAlgorithmOptimizedTravelTimePassenger extends AbstractTaxiAlgorithm
    implements TaxiAlgorithm {
  private final String name = "OptimizedTravelTime";
  private final AtomicLong counter = new AtomicLong(0);

  @Override
  public SimulationConfiguration getParameters() {
    return new SimulationConfiguration();
  }

  @Override
  public void setParameters(Map<String, Integer> parameters) {}

  @Override
  public AlgorithmResult nextStep(World world, Collection<Client> waitingClients) {
    counter.set(0);
    // FIXME this should be a typesafe method for centralized algorithms with centralized world
    // state
    if (!(world instanceof WorldObject worldObject))
      return fail(
          "Can not use this algorithm %s with a non WorldObject implementation (%s)!"
              .formatted(this.getName(), world.getClass().getSimpleName()));

    // todo fix this quick and dirty clients reset which removes already moving clients from taxis
    var taxiCandidates = new LinkedList<>(world.getTaxis());
    for (Taxi taxiCandidate : taxiCandidates) {
      world.mutate().clearTaxi(taxiCandidate);
    }

    var clients = world.getClients().stream().collect(Collectors.toMap(c -> c.getName(), c -> c));

    try {
      var bestSolution = findBestSolution((WorldObject) worldObject.snapshot(), 0L);
      for (Taxi taxi : bestSolution.v1().getTaxis()) {
        var originalTaxi =
            taxiCandidates.stream()
                .filter(t -> t.getName().equals(taxi.getName()))
                .findFirst()
                .orElseThrow();
        List<OrderNode> path = taxi.getTargets().toList();
        for (OrderNode orderNode : path) {
          world
              .mutate()
              .planClientForTaxi(
                  originalTaxi,
                  clients.get(orderNode.getClient()),
                  TargetList.sequentialOrders); // any order because the path is changed later on
        }
        world.mutate().planOrderPath(taxi, orders -> path);
      }
    } catch (Exception e) {
      return fail(e.getMessage());
    }
    log.warn("{} possible solutions analyzed.", counter.get());
    return ok();
  }

  private Tuple<WorldObject, Long> findBestSolution(WorldObject testWorld, Long travelTimeSum) {
    counter.incrementAndGet();
    var waitingClients = getClientsByModes(testWorld, Set.of(WAITING));
    if (waitingClients.isEmpty()) {
      return new Tuple<>(testWorld, travelTimeSum); // stop if world is fully planned
    }

    var taxis = testWorld.getTaxis();
    Tuple<WorldObject, Long> bestSolution = null;
    for (Client client : waitingClients) {
      for (Taxi taxi : taxis) {
        List<List<OrderNode>> allValidInsertions =
            findAllValidInsertions(
                taxi.getTargets().toList(),
                new OrderNode(client.getName(), client.getPosition()),
                new OrderNode(client.getName(), client.getTarget()),
                taxi.getCapacity());
        for (List<OrderNode> possiblePath : allValidInsertions) {
          var snapshot = (WorldObject) testWorld.snapshot();
          var snapshotTaxi =
              snapshot.getTaxis().stream()
                  .filter(t -> t.getName().equals(taxi.getName()))
                  .findFirst()
                  .orElseThrow();
          var snapshotClient =
              snapshot.getClients().stream()
                  .filter(c -> c.getName().equals(client.getName()))
                  .findFirst()
                  .orElseThrow();
          var oldTravelTime = travelTimeSumForTargets(snapshotTaxi);
          snapshot
              .mutate()
              .planClientForTaxi(snapshotTaxi, snapshotClient, TargetList.sequentialOrders);
          snapshot.mutate().planOrderPath(snapshotTaxi, orders -> possiblePath);
          var newTravelTime = travelTimeSumForTargets(snapshotTaxi);
          var diffTravelTime = newTravelTime - oldTravelTime;
          var solution = findBestSolution(snapshot, travelTimeSum + diffTravelTime);
          if (bestSolution == null || solution.v2() < bestSolution.v2()) {
            bestSolution = solution;
          }
        }
      }
    }

    return bestSolution;
  }

  private Long travelTimeSumForTargets(Taxi taxi) {
    var travelTime = 0L;
    var containedPassengers =
        taxi.getContainedPassengers().stream().map(Client::getName).collect(Collectors.toSet());
    var plannedPassengers =
        taxi.getPlannedPassengers().stream().map(Client::getName).collect(Collectors.toSet());
    var currentPosition = taxi.getPosition();
    var currentlyConnectedPassengers = containedPassengers.size() + plannedPassengers.size();

    for (OrderNode orderNode : taxi.getTargets().toList()) {
      var distance = orderNode.getPosition().distance(currentPosition);
      var timePassed = EntitySimulator.timePassed(taxi, distance);
      travelTime += timePassed * currentlyConnectedPassengers;
      // update for next order node
      if (containedPassengers.remove(orderNode.getClient())) {
        currentlyConnectedPassengers--;
      } else if (plannedPassengers.remove(orderNode.getClient())) {
        containedPassengers.add(orderNode.getClient());
      } else {
        throw new IllegalStateException(
            "Client %s was found in node path but not planned or contained in taxi %s"
                .formatted(orderNode.getClient(), taxi.getName()));
      }
      currentPosition = orderNode.getPosition();
    }

    return travelTime;
  }

  private static List<List<OrderNode>> findAllValidInsertions(
      List<OrderNode> path, OrderNode newPickup, OrderNode newDropOff, int taxiCapacity) {
    List<List<OrderNode>> validPaths = new ArrayList<>();

    for (int pickupIndex = 0; pickupIndex <= path.size(); pickupIndex++) {
      for (int dropoffIndex = pickupIndex + 1; dropoffIndex <= path.size() + 1; dropoffIndex++) {
        List<OrderNode> newPath = new ArrayList<>(path);
        newPath.add(pickupIndex, newPickup);
        newPath.add(dropoffIndex, newDropOff);

        if (isValidPath(newPath, taxiCapacity)) {
          validPaths.add(newPath);
        }
      }
    }

    return validPaths;
  }

  private static boolean isValidPath(List<OrderNode> path, int capacity) {
    Set<String> onBoard = new HashSet<>();

    for (OrderNode node : path) {
      if (!onBoard.contains(node.getClient())) {
        // pickup node
        onBoard.add(node.getClient());
      } else {
        if (!onBoard.contains(node.getClient())) {
          return false; // drop off without pickup
        }
        onBoard.remove(node.getClient());
      }

      if (onBoard.size() > capacity) {
        return false;
      }
    }

    return true;
  }
}
