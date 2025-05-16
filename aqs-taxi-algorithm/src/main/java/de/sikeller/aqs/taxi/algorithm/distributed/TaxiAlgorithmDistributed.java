package de.sikeller.aqs.taxi.algorithm.distributed;

import de.sikeller.aqs.model.*;
import de.sikeller.aqs.taxi.algorithm.AbstractTaxiAlgorithm;
import de.sikeller.aqs.taxi.algorithm.distributed.rqs.*;
import de.sikeller.aqs.taxi.algorithm.distributed.costing.*;

import java.util.*;
import java.util.concurrent.TimeUnit;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Getter
public class TaxiAlgorithmDistributed extends AbstractTaxiAlgorithm {

  private final String name = "Distributed";

  private final RangeQuerySystem rangeQuerySystem;
  private final CostCalculator costCalculator;
  private final DistributedCalculationTimer timer;

  // Map to keep track of the current search radius for each client across simulation steps
  private final Map<Client, Double> clientSearchRadii = new HashMap<>();

  private Map<String, Integer> parameters = new HashMap<>();
  private double referenceTaxiSpeed_kph = 80.0;

  public TaxiAlgorithmDistributed() {
    this.rangeQuerySystem = new SimulatedRangeQuerySystem();
    this.costCalculator = new SequentialCostCalculator();
    this.timer = new DistributedCalculationTimer();
    log.info(
        "TaxiAlgorithmDistributed (re)initialized with SimulatedRQS and SequentialCostCalculator.");
  }

  @Override
  public void setParameters(Map<String, Integer> parameters) {
    this.parameters = Objects.requireNonNullElseGet(parameters, HashMap::new);
    rangeQuerySystem.setParameters(this.parameters);
    costCalculator.setParameters(this.parameters);
  }

  @Override
  public SimulationConfiguration getParameters() {
    return new SimulationConfiguration(
        // Initial search Factor to calculate max search range for clients (Value in Percent).
        new AlgorithmParameter("InitialSearchRadiusFactor", 10),
        // Factor to increase search radius Factor if no taxi is found
        new AlgorithmParameter("RadiusIncreaseFactor", 2),
        // Calculate also Routes for full (at the time of the request) taxis -> 0 = no 1 = yes
        new AlgorithmParameter("CalculateFullTaxis", 0));
  }

  @Override
  public void init(World world) {
    clientSearchRadii.clear();
    timer.resetOverallTimer();
    // Read reference taxi speed from the world in km/h
    Optional<Taxi> anyTaxi = world.getTaxis().stream().findAny();
    this.referenceTaxiSpeed_kph = anyTaxi.map(Taxi::getCurrentSpeed).orElse(80.0);
    this.referenceTaxiSpeed_kph =
        this.referenceTaxiSpeed_kph > 0 ? this.referenceTaxiSpeed_kph : 80.0;

    log.info(
        "TaxiAlgorithmDistributed internal state cleared. Using reference taxi speed: {} km/h. Current parameters: {}",
        this.referenceTaxiSpeed_kph,
        this.parameters);
    // Pass parameters again in case they changed
    rangeQuerySystem.setParameters(this.parameters);
    costCalculator.setParameters(this.parameters);
  }

  @Override
  protected AlgorithmResult nextStep(World world, Collection<Client> waitingClients) {
    timer.startOverallNextStep();
    log.debug(
        "Executing nextStep for Distributed Algorithm at time {}. Waiting clients: {}",
        world.getCurrentTime(),
        waitingClients.size());

    final boolean calculateFullTaxis = parameters.getOrDefault("CalculateFullTaxis", 0) != 0;
    double initialSearchRadiusFactor =
        parameters.getOrDefault("InitialSearchRadiusFactor", 10) / 100.0;
    if (initialSearchRadiusFactor < 0 || initialSearchRadiusFactor > 1) {
      log.error(
          "InitialSearchRadiusFactor {} invalid. Setting to default value of 10%.",
          initialSearchRadiusFactor);
      initialSearchRadiusFactor = 0.1;
    }
    final double radiusIncreaseFactor = parameters.getOrDefault("RadiusIncreaseFactor", 2);
    final double taxiSpeed_mps = this.referenceTaxiSpeed_kph / 3.6;

    for (Client client : waitingClients) {
      long clientDecisionBlockStartTimeNanos = System.nanoTime();
      log.debug("Processing client: {}", client.getName());

      // --- Calculate Time and Distance Limits ---
      double maxClientWalkingTime_s;
      // Max distance taxi can be from client start position until it makes more sense to walk
      double maxTheoreticalPickupDistance_m;
      double clientDirectDistance_m = client.getPosition().distance(client.getTarget());
      double clientWalkingSpeed_mps = client.getCurrentSpeed() / 3.6;

      if (clientDirectDistance_m <= 0) {
        maxClientWalkingTime_s = 0;
        maxTheoreticalPickupDistance_m = 0;
        log.warn("Client {}: Zero direct distance.", client.getName());
      } else if (taxiSpeed_mps > clientWalkingSpeed_mps) {
        maxClientWalkingTime_s = clientDirectDistance_m / clientWalkingSpeed_mps;
        maxTheoreticalPickupDistance_m =
            clientDirectDistance_m * (taxiSpeed_mps / clientWalkingSpeed_mps - 1.0);
        log.trace(
            "Client {}: Max walking time={}, Max theoretical pickup distance={}",
            client.getName(),
            String.format("%.2f", maxClientWalkingTime_s),
            String.format("%.2f", maxTheoreticalPickupDistance_m));
      } else { // Taxi not faster
        maxClientWalkingTime_s = clientDirectDistance_m / clientWalkingSpeed_mps;
        maxTheoreticalPickupDistance_m = 0.0; // Taxi not faster than walking
        log.trace(
            "Client {}: Max walking time={}s. Taxi speed ({}m/h) <= walking speed ({}m/s), theoretical pickup distance is 0.",
            client.getName(),
            String.format("%.2f", maxClientWalkingTime_s),
            String.format("%.2f", taxiSpeed_mps),
            String.format("%.2f", clientWalkingSpeed_mps));
      }

      // Get or initialize the search radius for this client
      final double initialSearchRadius = maxTheoreticalPickupDistance_m * initialSearchRadiusFactor;
      double currentSearchRadius =
          clientSearchRadii.computeIfAbsent(client, k -> initialSearchRadius);
      boolean taxiFound = false;

      long clientDecisionBlockTimeNanos = System.nanoTime() - clientDecisionBlockStartTimeNanos;

      // 1. Query the Range Query System
      final long rqsCallStartTimeNanos = System.nanoTime();
      Position start = client.getPosition();
      Position target = client.getTarget();
      Set<Taxi> candidateTaxis =
          rangeQuerySystem.findTaxisInRange(world, start, target, currentSearchRadius);
      timer.recordRqsQueryTime(System.nanoTime() - rqsCallStartTimeNanos);
      log.debug(
          "Client {} found {} candidates within radius {}",
          client.getName(),
          candidateTaxis.size(),
          currentSearchRadius);

      if (!candidateTaxis.isEmpty()) {
        // 2. Get offers from candidate taxis
        Map<Taxi, CostCalculationResult> offers = new HashMap<>();
        for (Taxi taxi : candidateTaxis) {
          if (calculateFullTaxis || taxi.hasCapacity()) {
            CostCalculationResult calcResult =
                costCalculator.calculateMarginalCost(taxi, client, maxClientWalkingTime_s);
            timer.recordSingleTaxiRouteCalcTime(taxi, calcResult.calculationTimeNanos());
            log.debug("Route Calculation Time(ns): {}", calcResult.calculationTimeNanos());

            if (calcResult.cost() != CostCalculationResult.INFEASIBLE_COST) {
              offers.put(taxi, calcResult);
              log.trace(
                  "Client {} received FEASIBLE offer from Taxi {} with cost {} (calc time: {} ms)",
                  client.getName(),
                  taxi.getName(),
                  calcResult.cost(),
                  TimeUnit.NANOSECONDS.toMillis(calcResult.calculationTimeNanos()));
            } else {
              log.trace(
                  "Client {} - Taxi {} reported INFEASIBLE Capacity/Time Limit (calc time: {} ms)",
                  client.getName(),
                  taxi.getName(),
                  TimeUnit.NANOSECONDS.toMillis(calcResult.calculationTimeNanos()));
            }
          } else {
            log.trace(
                "Client {} - Taxi {} skipped (no capacity)", client.getName(), taxi.getName());
          }
        }

        clientDecisionBlockStartTimeNanos = System.nanoTime();

        // 3. Client chooses the best offer
        Optional<Map.Entry<Taxi, CostCalculationResult>> bestOffer =
            offers.entrySet().stream()
                .min(Comparator.comparingDouble(entry -> entry.getValue().cost()));

        if (bestOffer.isPresent()) {
          final Taxi chosenTaxi = bestOffer.get().getKey();
          final CostCalculationResult chosenResult = bestOffer.get().getValue();
          final List<OrderNode> optimalRoute = chosenResult.optimalRoute();
          if (optimalRoute == null || optimalRoute.isEmpty()) {
            log.error(
                "Taxi {}: Best offer for client {} had valid cost but NULL/empty optimal route! Skipping assignment.",
                chosenTaxi.getName(),
                client.getName());
          } else {
            log.debug(
                "Client {} chooses Taxi {} with cost {}",
                client.getName(),
                chosenTaxi.getName(),
                chosenResult.cost());
            // 4. Finalize assignment
            // ToDo: add new API toWorldMutator, e.x. setExplicitRoute(Taxi taxi, List<OrderNode>
            // routeNodes).
            world.mutate().planClientForTaxi(chosenTaxi, client, TargetList.sequentialOrders);
            OrderFlattenFunction routeSetter = (existingOrders) -> optimalRoute;
            world.mutate().planOrderPath(chosenTaxi, routeSetter);
            log.info(
                "Assigned Client {} to Taxi {} with explicit Route.",
                client.getName(),
                chosenTaxi.getName());

            // Reset search radius for the next time
            clientSearchRadii.put(client, initialSearchRadius);
            taxiFound = true;
          }
        } else {
          log.debug(
              "Client {} found candidates, but none provided a feasible offer.", client.getName());
        }
      } // end if candidateTaxis not empty

      // 5. Handle case where no taxi was found or assigned
      if (!taxiFound) {
        log.debug(
            "Client {} could not find a suitable taxi in radius {}.",
            client.getName(),
            currentSearchRadius);
        double nextRadius = currentSearchRadius * radiusIncreaseFactor;

        if (nextRadius <= maxTheoreticalPickupDistance_m) {
          clientSearchRadii.put(client, nextRadius);
          log.debug("Client {} search radius increased to {}", client.getName(), nextRadius);
        } else {
          clientSearchRadii.put(client, maxTheoreticalPickupDistance_m); // Cap the radius
          log.debug(
              "Client {} reached max search radius {}. Will keep trying at max radius or client should consider walking.",
              client.getName(),
              maxTheoreticalPickupDistance_m);
        }
      }
      clientDecisionBlockTimeNanos =
          System.nanoTime() - clientDecisionBlockStartTimeNanos + clientDecisionBlockTimeNanos;
      timer.recordClientDecisionTime(clientDecisionBlockTimeNanos);
    } // End of client loop
    timer.stopOverallNextStepAndAggregate();
    timer.logLastStepTimes(world.getCurrentTime());

    if (getClientsByModes(world, Set.of(ClientMode.WAITING)).isEmpty()) {
      return stop("All previously waiting clients have been assigned or processed for this step.");
    } else {
      // returns sum of all three bottleneck times
      return ok(timer.getLastSimulatedTotalParallelTimeNanos());
    }
  }
}
