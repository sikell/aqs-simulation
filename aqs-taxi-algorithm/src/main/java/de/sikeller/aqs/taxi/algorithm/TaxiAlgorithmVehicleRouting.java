package de.sikeller.aqs.taxi.algorithm;

import com.google.ortools.Loader;
import com.google.ortools.constraintsolver.*;
import com.google.protobuf.Duration;
import de.sikeller.aqs.model.*;
import de.sikeller.aqs.model.AlgorithmResult;
import java.util.*;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Getter
@Setter
public class TaxiAlgorithmVehicleRouting extends AbstractTaxiAlgorithm implements TaxiAlgorithm {
  private static final String ENTITY_TYPE_CLIENT_START = "[start-%s]";
  private static final String ENTITY_TYPE_CLIENT_TARGET = "[target-%s]";
  private static final String ENTITY_TYPE_TAXI = "[taxiSpawn-%s]";
  private static final String ENTITY_TYPE_DEPOT = "[taxiDepot]";
  private Map<String, Integer> parameters;
  private final String name = "VehicleRouting";

  public TaxiAlgorithmVehicleRouting() {
    log.info("Load OR-Tools...");
    Loader.loadNativeLibraries();
  }

  @Override
  public SimulationConfiguration getParameters() {
    return new SimulationConfiguration();
  }

  @Override
  public AlgorithmResult nextStep(World world, Collection<Client> waitingClients) {
    var taxiCandidates = new LinkedList<>(world.getTaxis());
    var taxiCandidatesCount = taxiCandidates.size();
    if (taxiCandidates.isEmpty()) return stop("No taxis with capacity found.");

    var notFinishedClients =
        new LinkedList<>(
            getClientsByModes(
                world, Set.of(ClientMode.WAITING, ClientMode.PLANNED, ClientMode.MOVING)));
    var notFinishedClientsCount = notFinishedClients.size();

    /* waiting client positions + client target positions + taxi positions + 1*depot */
    int entityCount = 2 * notFinishedClientsCount + taxiCandidatesCount + 1;

    String[] entityTypes = new String[entityCount];
    Position[] entityPositions = new Position[entityCount];
    int[] demands = new int[entityCount];
    int[][] pickupDeliveries = new int[notFinishedClientsCount][2];
    int i = 0;
    int p = 0;
    for (Client waitingClient : notFinishedClients) {
      entityTypes[i] = ENTITY_TYPE_CLIENT_START.formatted(waitingClient.getName());
      entityPositions[i] = waitingClient.getPosition();
      demands[i] = 1;
      pickupDeliveries[p][0] = i;
      i++;
      entityTypes[i] = ENTITY_TYPE_CLIENT_TARGET.formatted(waitingClient.getName());
      entityPositions[i] = waitingClient.getTarget();
      demands[i] = -1;
      pickupDeliveries[p][1] = i;
      i++;
      p++;
    }
    int[] ends = new int[taxiCandidatesCount];
    int[] starts = new int[taxiCandidatesCount];
    long[] taxiCapacities = new long[taxiCandidatesCount];
    int f = 0;
    for (Taxi taxiCandidate : taxiCandidates) {
      entityTypes[i] = ENTITY_TYPE_TAXI.formatted(taxiCandidate.getName());
      entityPositions[i] = taxiCandidate.getPosition();
      demands[i] = 0;
      starts[f] = i;
      // ends should be arbitrary - so set distance matrix to depot position (last entry) to which
      // the distance is always zero:
      ends[f] = entityCount - 1;
      taxiCapacities[f] = taxiCandidate.getCapacity();
      i++;
      f++;
    }
    entityTypes[i] = ENTITY_TYPE_DEPOT;
    entityPositions[i] = null;
    demands[i] = 0;

    long[][] distanceMatrix = createDistanceMatrix(entityPositions);

    RoutingIndexManager manager =
        new RoutingIndexManager(distanceMatrix.length, taxiCandidatesCount, starts, ends);

    RoutingModel routing = new RoutingModel(manager);

    final int transitCallbackIndex =
        routing.registerTransitCallback(
            (long fromIndex, long toIndex) -> {
              int fromNode = manager.indexToNode(fromIndex);
              int toNode = manager.indexToNode(toIndex);
              return distanceMatrix[fromNode][toNode];
            });
    routing.setArcCostEvaluatorOfAllVehicles(transitCallbackIndex);
    routing.addDimension(
        transitCallbackIndex,
        0, // no slack
        400000, // todo max distance capa
        true, // start cumul to zero
        "Distance");

    // add taxi capacity constraint:
    final int loadCallbackIndex =
        routing.registerUnaryTransitCallback((long index) -> demands[manager.indexToNode(index)]);
    routing.addDimensionWithVehicleCapacity(
        loadCallbackIndex,
        0, // no slack
        taxiCapacities,
        true, // start cumul to zero
        "Capacity");

    RoutingDimension distanceDimension = routing.getMutableDimension("Distance");
    distanceDimension.setGlobalSpanCostCoefficient(100);

    Solver solver = routing.solver();
    for (int[] request : pickupDeliveries) {
      long pickupIndex = manager.nodeToIndex(request[0]);
      long deliveryIndex = manager.nodeToIndex(request[1]);
      routing.addPickupAndDelivery(pickupIndex, deliveryIndex);
      // client has to be picked up and delivered by same taxi:
      solver.addConstraint(
          solver.makeEquality(routing.vehicleVar(pickupIndex), routing.vehicleVar(deliveryIndex)));
      // client has to be picked up before delivery:
      solver.addConstraint(
          solver.makeLessOrEqual(
              distanceDimension.cumulVar(pickupIndex), distanceDimension.cumulVar(deliveryIndex)));
    }

    RoutingSearchParameters searchParameters =
        main.defaultRoutingSearchParameters().toBuilder()
            .setFirstSolutionStrategy(FirstSolutionStrategy.Value.PARALLEL_CHEAPEST_INSERTION)
            .setTimeLimit(Duration.newBuilder().setSeconds(10).build())
            .build();

    Assignment solution = routing.solveWithParameters(searchParameters);
    if (solution == null) return fail("Can not find a solution!");

    printSolution(taxiCandidatesCount, routing, manager, solution, demands, entityTypes);

    // todo fix this quick and dirty clients reset which removes already moving clients from taxis
    for (Taxi taxiCandidate : taxiCandidates) {
      world.mutate().clearTaxi(taxiCandidate);
    }

    for (int l = 0; l < taxiCandidatesCount; ++l) {
      long index = routing.start(l);
      Taxi taxi = taxiCandidates.get(l);
      List<OrderNode> path = new LinkedList<>();
      while (!routing.isEnd(index)) {
        index = solution.value(routing.nextVar(index));
        int entityIndex = manager.indexToNode(index);
        if (entityIndex / 2 < notFinishedClientsCount) {
          path.add(
              new OrderNode(notFinishedClients.get(entityIndex / 2), entityPositions[entityIndex]));
          if (entityIndex % 2 == 0) {
            var client = notFinishedClients.get(entityIndex / 2);
            world.mutate().planClientForTaxi(taxi, client,  TargetList.sequentialOrders);
          }
        }
      }
      // todo fix this if the spawn window of clients are larger and clients dynamically spawns...
      world.mutate().planOrderPath(taxi, orders -> path);
    }
    return ok();
  }

  private static long[][] createDistanceMatrix(Position[] entityPositions) {
    var entityCount = entityPositions.length;
    long[][] distanceMatrix = new long[entityCount][entityCount];
    for (int k = 0; k < entityCount; k++) {
      var c = entityPositions[k];
      for (int j = 0; j < entityCount; j++) {
        var o = entityPositions[j];
        boolean isVirtualDepot = c == null || o == null;
        distanceMatrix[k][j] = isVirtualDepot ? 0 : Math.round(c.distance(o));
      }
    }
    return distanceMatrix;
  }

  static void printSolution(
      int taxiCandidatesCount,
      RoutingModel routing,
      RoutingIndexManager manager,
      Assignment solution,
      int[] demands,
      String[] entityTypes) {
    log.debug("Objective : {}", solution.objectiveValue());
    long maxRouteDistance = 0;
    for (int i = 0; i < taxiCandidatesCount; ++i) {
      long index = routing.start(i);
      log.debug("Route for Vehicle {}:", i);
      long routeDistance = 0;
      long routeLoad = 0;
      StringBuilder route = new StringBuilder();
      while (!routing.isEnd(index)) {
        int nodeIndex = manager.indexToNode(index);
        routeLoad += demands[nodeIndex];
        route.append(entityTypes[nodeIndex]).append("(").append(routeLoad).append(") -> ");
        long previousIndex = index;
        index = solution.value(routing.nextVar(index));
        routeDistance += routing.getArcCostForVehicle(previousIndex, index, i);
      }
      route.append(entityTypes[manager.indexToNode(index)]);
      log.debug("{}", route);
      log.debug("Distance of the route: {}m", routeDistance);
      maxRouteDistance = Math.max(routeDistance, maxRouteDistance);
    }
    log.debug("Maximum of the route distances: {}m", maxRouteDistance);
  }
}
