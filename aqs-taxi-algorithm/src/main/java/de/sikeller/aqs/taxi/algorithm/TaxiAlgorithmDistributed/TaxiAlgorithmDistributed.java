package de.sikeller.aqs.taxi.algorithm.TaxiAlgorithmDistributed;

import de.sikeller.aqs.model.*;
import de.sikeller.aqs.taxi.algorithm.AbstractTaxiAlgorithm;
import de.sikeller.aqs.taxi.algorithm.TaxiAlgorithmDistributed.rqs.*;
import de.sikeller.aqs.taxi.algorithm.TaxiAlgorithmDistributed.costing.*;

import java.util.*;
import java.util.concurrent.TimeUnit;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Getter
public class TaxiAlgorithmDistributed extends AbstractTaxiAlgorithm {

  private final String name = "Distributed";

  private final transient List<Long> simulatedParallelCalcTimesNanos = new ArrayList<>();

  private final RangeQuerySystem rangeQuerySystem;
  private final CostCalculator costCalculator;

  // Map to keep track of the current search radius for each client across simulation steps
  private final Map<Client, Double> clientSearchRadii = new HashMap<>();

  private Map<String, Integer> parameters = new HashMap<>();

  public TaxiAlgorithmDistributed() {
    this.rangeQuerySystem = new SimulatedRangeQuerySystem();
    this.costCalculator = new SequentialCostCalculator();
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
        new AlgorithmParameter("InitialSearchRadius", 50),
        // Factor to increase search radius if no taxi is found
        new AlgorithmParameter("RadiusIncreaseFactor", 2),
        // Maximum search radius multiplier (related to walking speed comparison) - Placeholder
        new AlgorithmParameter("MaxRadiusMultiplier", 10),
        // Calculate also Routes for full (at the time of the request) taxis -> 0 = no 1 = yes
        new AlgorithmParameter("CalculateFullTaxis", 0));
  }

  @Override
  public void init(World world) {
    clientSearchRadii.clear();
    simulatedParallelCalcTimesNanos.clear();
    log.info(
        "TaxiAlgorithmDistributed internal state cleared. Current parameters: {}", this.parameters);
    // Pass parameters again in case they changed
    rangeQuerySystem.setParameters(this.parameters);
    costCalculator.setParameters(this.parameters);
  }

  @Override
  protected AlgorithmResult nextStep(World world, Set<Client> waitingClients) {
    log.debug(
        "Executing nextStep for Distributed Algorithm with {} waiting clients.",
        waitingClients.size());

    final boolean calculateFullTaxis = parameters.getOrDefault("CalculateFullTaxis", 0) != 0;
    final double initialSearchRadius = parameters.getOrDefault("InitialSearchRadius", 50);
    final double radiusIncreaseFactor = parameters.getOrDefault("RadiusIncreaseFactor", 2);
    final double maxRadiusMultiplier = parameters.getOrDefault("MaxRadiusMultiplier", 10);
    final double maxRadius = initialSearchRadius * maxRadiusMultiplier;

    simulatedParallelCalcTimesNanos.clear(); // Clear times for this step

    for (Client client : waitingClients) {
      log.debug("Processing client: {}", client.getName());

      // Get or initialize the search radius for this client
      double currentSearchRadius =
          clientSearchRadii.computeIfAbsent(client, k -> initialSearchRadius);
      boolean taxiFound = false;
      // Track max time for this client's requests to simulate parallelism
      long maxCalcTimeNanosCurrentClient = 0;

      // 1. Query the Range Query System
      Position start = client.getPosition();
      Position target = client.getTarget();
      Set<Taxi> candidateTaxis =
          rangeQuerySystem.findTaxisInRange(world, start, target, currentSearchRadius);
      log.debug(
          "Client {} found {} candidates within radius {}",
          client.getName(),
          candidateTaxis.size(),
          currentSearchRadius);

      if (!candidateTaxis.isEmpty()) {
        // 2. Get offers from candidate taxis
        Map<Taxi, Double> offers = new HashMap<>();
        for (Taxi taxi : candidateTaxis) {
          if (calculateFullTaxis || taxi.hasCapacity()) {
            CostCalculationResult calcResult = costCalculator.calculateMarginalCost(taxi, client);
            maxCalcTimeNanosCurrentClient =
                Math.max(maxCalcTimeNanosCurrentClient, calcResult.calculationTimeNanos());

            if (calcResult.cost() != CostCalculationResult.INFEASIBLE_COST) {
              offers.put(taxi, calcResult.cost());
              log.trace(
                  "Client {} received offer from Taxi {} with cost {} (calc time: {} ms)",
                  client.getName(),
                  taxi.getName(),
                  calcResult.cost(),
                  TimeUnit.NANOSECONDS.toMillis(calcResult.calculationTimeNanos()));
            } else {
              log.trace(
                  "Client {} - Taxi {} reported infeasible (calc time: {} ms)",
                  client.getName(),
                  taxi.getName(),
                  TimeUnit.NANOSECONDS.toMillis(calcResult.calculationTimeNanos()));
            }
          } else {
            log.trace(
                "Client {} - Taxi {} skipped (no capacity)", client.getName(), taxi.getName());
          }
        }
        // Add the simulated parallel time for this client's request phase to the list
        if (!candidateTaxis.isEmpty()) { // Only add if calculations were performed
          simulatedParallelCalcTimesNanos.add(maxCalcTimeNanosCurrentClient);
          log.debug(
              "Client {} - Max calculation time (simulated parallel): {} ms",
              client.getName(),
              TimeUnit.NANOSECONDS.toMillis(maxCalcTimeNanosCurrentClient));
        }

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
          planClientForTaxi(
              chosenTaxi, client, world.getCurrentTime(), TargetList.sequentialOrders);
          log.info("Assigned Client {} to Taxi {}", client.getName(), chosenTaxi.getName());

          // Reset search radius for the next time
          clientSearchRadii.put(client, initialSearchRadius);
          taxiFound = true;

        } else {
          log.debug("Client {} received offers, but none were suitable.", client.getName());
        }
      } // end if candidateTaxis not empty

      // 5. Handle case where no taxi was found or assigned
      if (!taxiFound) {
        log.debug(
            "Client {} could not find a suitable taxi in radius {}.",
            client.getName(),
            currentSearchRadius);
        double nextRadius = currentSearchRadius * radiusIncreaseFactor;

        if (nextRadius <= maxRadius) {
          clientSearchRadii.put(client, nextRadius);
          log.debug("Client {} search radius increased to {}", client.getName(), nextRadius);
        } else {
          clientSearchRadii.put(client, maxRadius); // Cap the radius
          log.warn(
              "Client {} reached max search radius {}. Will keep trying at max radius or client should consider walking.",
              client.getName(),
              maxRadius);
        }
      }
    } // End of client loop

    // --- Log overall simulated time for the step ---
    // We sum the max times for each client processed in this step. This assumes client requests
    // happen sequentially,
    // but the taxi calculations for *one* client happen in parallel.
    long totalSimulatedParallelTimeNanos =
        simulatedParallelCalcTimesNanos.stream().mapToLong(Long::longValue).sum();
    if (totalSimulatedParallelTimeNanos > 0) {
      log.info(
          "Step {}: Total simulated parallel calculation time: {} ms",
          world.getCurrentTime(),
          TimeUnit.NANOSECONDS.toMillis(totalSimulatedParallelTimeNanos));
      // ToDo: Should add rest of calculation time to this time (e.g., for taxi assignment).
      // Should we report the calculation time of the RQS? Optional?
      // TODO: Decide how to formally report this time for the thesis.
      // Option A: Just log it.
      // Option B: Store it in a member variable for later retrieval.
      // Option C: Modify AlgorithmResult or StatsCollector.
    }

    if (getClientsByModes(world, Set.of(ClientMode.WAITING)).isEmpty()) {
      return stop("All previously waiting clients have been assigned or processed for this step.");
    } else {
      return ok();
    }
  }
}
