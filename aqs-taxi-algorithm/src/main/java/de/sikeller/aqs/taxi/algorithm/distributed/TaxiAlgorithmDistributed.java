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
        // Initial search range for clients
        // todo: try to use a factor of the distance to the target
        new AlgorithmParameter("InitialSearchRadius", 50),
        // Factor to increase search radius if no taxi is found
        new AlgorithmParameter("RadiusIncreaseFactor", 2),
        // Calculate also Routes for full (at the time of the request) taxis -> 0 = no 1 = yes
        new AlgorithmParameter("CalculateFullTaxis", 0));
  }

  @Override
  public void init(World world) {
    clientSearchRadii.clear();
    timer.resetTimer();
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
  protected AlgorithmResult nextStep(World world, Set<Client> waitingClients) {
    log.debug(
        "Executing nextStep for Distributed Algorithm at time {}. Waiting clients: {}",
        world.getCurrentTime(),
        waitingClients.size());

    timer.startNextStep();

    final boolean calculateFullTaxis = parameters.getOrDefault("CalculateFullTaxis", 0) != 0;
    final double initialSearchRadius = parameters.getOrDefault("InitialSearchRadius", 50);
    final double radiusIncreaseFactor = parameters.getOrDefault("RadiusIncreaseFactor", 2);
    final double taxiSpeed_mps = this.referenceTaxiSpeed_kph / 3.6;

    for (Client client : waitingClients) {
      log.debug("Processing client: {}", client.getName());

      // --- Calculate Time and Distance Limits ---
      double maxClientWalkingTime_s = Double.POSITIVE_INFINITY;
      // Max distance taxi can be from client start position until it makes more sense to walk
      double maxTheoreticalPickupDistance_m = Double.POSITIVE_INFINITY;
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

      double currentSearchRadius = Math.min(initialSearchRadius, maxTheoreticalPickupDistance_m);

      // Get or initialize the search radius for this client
      final double defaultSearchRadius = currentSearchRadius;
      currentSearchRadius = clientSearchRadii.computeIfAbsent(client, k -> defaultSearchRadius);
      boolean taxiFound = false;

      // 1. Query the Range Query System
      timer.startRQSCalc();
      Position start = client.getPosition();
      Position target = client.getTarget();
      Set<Taxi> candidateTaxis =
          rangeQuerySystem.findTaxisInRange(world, start, target, currentSearchRadius);
      timer.stopRQSCalc();
      log.debug(
          "Client {} found {} candidates within radius {}",
          client.getName(),
          candidateTaxis.size(),
          currentSearchRadius);

      if (!candidateTaxis.isEmpty()) {
        // 2. Get offers from candidate taxis
        Map<Taxi, Double> offers = new HashMap<>();

        timer.startRouteCalc();
        for (Taxi taxi : candidateTaxis) {
          if (calculateFullTaxis || taxi.hasCapacity()) {
            CostCalculationResult calcResult =
                costCalculator.calculateMarginalCost(taxi, client, maxClientWalkingTime_s);
            timer.newSingleRouteCalc(calcResult.calculationTimeNanos());
            System.out.println("Route Calculation Time(ns): " + calcResult.calculationTimeNanos());

            if (calcResult.cost() != CostCalculationResult.INFEASIBLE_COST) {
              offers.put(taxi, calcResult.cost());
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
        timer.stopRouteCalc();

        // 3. Client chooses the best offer
        Optional<Map.Entry<Taxi, Double>> bestOffer =
            offers.entrySet().stream().min(Map.Entry.comparingByValue());

        if (bestOffer.isPresent()) {
          Taxi chosenTaxi = bestOffer.get().getKey();
          double chosenCost = bestOffer.get().getValue();
          log.debug(
              "Client {} chooses Taxi {} with cost {}",
              client.getName(),
              chosenTaxi.getName(),
              chosenCost);

          // 4. Finalize assignment
          // TODO: Use correct order-mechanism for TargetList.order
          world.mutate().planClientForTaxi(chosenTaxi, client, TargetList.sequentialOrders);
          log.info("Assigned Client {} to Taxi {}", client.getName(), chosenTaxi.getName());

          // Reset search radius for the next time
          clientSearchRadii.put(client, initialSearchRadius);
          taxiFound = true;

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
          log.warn(
              "Client {} reached max search radius {}. Will keep trying at max radius or client should consider walking.",
              client.getName(),
              maxTheoreticalPickupDistance_m);
        }
      }
    } // End of client loop
    timer.stopNextStep();

    // --- Log overall simulated time for the step ---
    // We sum the max times for each client processed in this step. This assumes client requests
    // happen sequentially,
    // but the taxi calculations for *one* client happen in parallel.
    timer.logLastStepTimes(world.getCurrentTime());
    // TODO: Decide how to formally report this time.
    // Option A: Just log it.
    // Option B: Modify AlgorithmResult or StatsCollector.

    if (getClientsByModes(world, Set.of(ClientMode.WAITING)).isEmpty()) {
      return stop("All previously waiting clients have been assigned or processed for this step.");
    } else {
      return ok();
    }
  }

  @Getter
  private static class DistributedCalculationTimer {
    // all Timers in nanoseconds
    // ges. Timers
    /** Contains overall calculation Times of each Simulation nextStep call */
    private final List<Long> simulatedParallelNextStepTimes = new LinkedList<>();

    /** Contains accumulated RQS calculation Times of each Simulation nextStep call */
    private final List<Long> simulatedRQSCalcTimes = new LinkedList<>();

    /** Contains accumulated Route calculation Times of each Simulation nextStep call */
    private final List<Long> simulatedParallelRouteCalcTimes = new LinkedList<>();

    // helpers while calculating a single nextStep
    private long rqsCalcTime;
    private long routeCalcTime;
    private long highestSingleRouteCalcTime;
    private long rqsStartTime;
    private long nextStepStartTime;

    protected void startNextStep() {
      resetHelpers();
      nextStepStartTime = System.nanoTime();
    }

    protected void stopNextStep() {
      long nextStepTime = System.nanoTime() - nextStepStartTime;
      simulatedParallelNextStepTimes.add(nextStepTime);
      simulatedRQSCalcTimes.add(rqsCalcTime);
      simulatedParallelRouteCalcTimes.add(routeCalcTime);
    }

    protected void startRQSCalc() {
      rqsStartTime = System.nanoTime();
    }

    protected void stopRQSCalc() {
      rqsCalcTime += System.nanoTime() - rqsStartTime;
    }

    protected void startRouteCalc() {
      highestSingleRouteCalcTime = 0;
    }

    protected void stopRouteCalc() {
      // simulate parallelism by adding the longest single route calculation time
      routeCalcTime += highestSingleRouteCalcTime;
    }

    protected void newSingleRouteCalc(long time) {
      highestSingleRouteCalcTime = Math.max(highestSingleRouteCalcTime, time);
    }

    private void resetHelpers() {
      rqsCalcTime = 0;
      routeCalcTime = 0;
      highestSingleRouteCalcTime = 0;
    }

    protected void resetTimer() {
      simulatedParallelNextStepTimes.clear();
      simulatedRQSCalcTimes.clear();
      simulatedParallelRouteCalcTimes.clear();
      resetHelpers();
    }

    protected void logLastStepTimes(long currentSimTime) {
      log.info(
          """
            Simulated Times step {}:
            Total: {} ns
            RQS: {} ns
            Route Calculation: {} ns""",
          currentSimTime,
          simulatedParallelNextStepTimes.getLast(),
          simulatedRQSCalcTimes.getLast(),
          simulatedParallelRouteCalcTimes.getLast());
    }

    protected void logTotalTimes() {
      log.info(
          """
             Total Times:
             Total Calculation: {} ms
             Max Calculation: {} ms
             RQS Calculations: {} ms
             Max RQS : {} ms
             Route Calculations: {} ms
             Max Route Calculation: {} ms""",
          TimeUnit.NANOSECONDS.toMillis(
              simulatedParallelNextStepTimes.stream().mapToLong(Long::longValue).sum()),
          TimeUnit.NANOSECONDS.toMillis(
              simulatedParallelNextStepTimes.stream().mapToLong(Long::longValue).max().orElse(0)),
          TimeUnit.NANOSECONDS.toMillis(
              simulatedRQSCalcTimes.stream().mapToLong(Long::longValue).sum()),
          TimeUnit.NANOSECONDS.toMillis(
              simulatedRQSCalcTimes.stream().mapToLong(Long::longValue).max().orElse(0)),
          TimeUnit.NANOSECONDS.toMillis(
              simulatedParallelRouteCalcTimes.stream().mapToLong(Long::longValue).sum()),
          TimeUnit.NANOSECONDS.toMillis(
              simulatedParallelRouteCalcTimes.stream().mapToLong(Long::longValue).max().orElse(0)));
    }
  }
}
