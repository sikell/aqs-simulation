package de.sikeller.aqs.taxi.algorithm.distributed;

import de.sikeller.aqs.model.*;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

@Slf4j
@Getter
public class DistributedCalculationTimer {
  // --- Listen für die aggregierten Bottleneck-Zeiten pro Step ---
  private final List<Long> step_ActualWallClockTimesNanos = new LinkedList<>();
  private final List<Long> step_SimulatedRqsBottleneckTimesNanos = new LinkedList<>();
  private final List<Long> step_SimulatedTaxiBottleneckTimesNanos = new LinkedList<>();
  private final List<Long> step_SimulatedClientDecisionBottleneckTimesNanos = new LinkedList<>();
  // Sum of the three bottlenecks
  private final List<Long> step_SimulatedTotalParallelTimesNanos = new LinkedList<>();

  // helper fur current nextStep call (reset after each step)
  private long wallClockStepStartTimeNanos;
  // RQS: list of all individual RQS durations in this step
  private List<Long> currentStepIndividualRqsTimesNanos;
  // Taxis: Map of accumulated processing times per taxi in this step
  private Map<Taxi, Long> currentStepTaxiProcessingTimesNanos;
  // Clients: List of all individual client decision times in this step
  private List<Long> currentStepIndividualClientDecisionTimesNanos;

  protected void startOverallNextStep() {
    resetCurrentStepHelpers();
    wallClockStepStartTimeNanos = System.nanoTime();
  }

  protected void stopOverallNextStepAndAggregate() {
    // 1. Wall time for the current step
    long actualWallClockTime = System.nanoTime() - wallClockStepStartTimeNanos;
    step_ActualWallClockTimesNanos.add(actualWallClockTime);

    // 2. RQS Bottleneck-Time for the current step
    long rqsBottleneck = 0;
    if (currentStepIndividualRqsTimesNanos != null
        && !currentStepIndividualRqsTimesNanos.isEmpty()) {
      rqsBottleneck =
          currentStepIndividualRqsTimesNanos.stream().mapToLong(Long::longValue).max().orElse(0L);
    }
    step_SimulatedRqsBottleneckTimesNanos.add(rqsBottleneck);

    // 3. Taxi Bottleneck-Time for the current step
    long taxiBottleneck = 0;
    if (currentStepTaxiProcessingTimesNanos != null
        && !currentStepTaxiProcessingTimesNanos.isEmpty()) {
      taxiBottleneck =
          currentStepTaxiProcessingTimesNanos.values().stream()
              .mapToLong(Long::longValue)
              .max()
              .orElse(0L);
    }
    step_SimulatedTaxiBottleneckTimesNanos.add(taxiBottleneck);

    // 4. Client Decision Bottleneck-Time for the current step
    long clientDecisionBottleneck = 0;
    if (currentStepIndividualClientDecisionTimesNanos != null
        && !currentStepIndividualClientDecisionTimesNanos.isEmpty()) {
      clientDecisionBottleneck =
          currentStepIndividualClientDecisionTimesNanos.stream()
              .mapToLong(Long::longValue)
              .max()
              .orElse(0L);
    }
    step_SimulatedClientDecisionBottleneckTimesNanos.add(clientDecisionBottleneck);

    // 5. entire simulated parallel time for this step
    step_SimulatedTotalParallelTimesNanos.add(
        rqsBottleneck + taxiBottleneck + clientDecisionBottleneck);
  }

  // --- Methods for adding individual times during the current step ---
  protected void recordRqsQueryTime(long durationNanos) {
    if (currentStepIndividualRqsTimesNanos == null) {
      currentStepIndividualRqsTimesNanos = new ArrayList<>();
    }
    currentStepIndividualRqsTimesNanos.add(durationNanos);
  }

  protected void recordSingleTaxiRouteCalcTime(Taxi taxi, long durationNanos) {
    if (currentStepTaxiProcessingTimesNanos == null) {
      currentStepTaxiProcessingTimesNanos = new HashMap<>();
    }
    currentStepTaxiProcessingTimesNanos.merge(taxi, durationNanos, Long::sum);
  }

  protected void recordClientDecisionTime(long durationNanos) {
    if (currentStepIndividualClientDecisionTimesNanos == null) {
      currentStepIndividualClientDecisionTimesNanos = new ArrayList<>();
    }
    currentStepIndividualClientDecisionTimesNanos.add(durationNanos);
  }

  // --- Reset ---
  private void resetCurrentStepHelpers() {
    if (currentStepIndividualRqsTimesNanos != null) currentStepIndividualRqsTimesNanos.clear();
    else currentStepIndividualRqsTimesNanos = new ArrayList<>();

    if (currentStepTaxiProcessingTimesNanos != null) currentStepTaxiProcessingTimesNanos.clear();
    else currentStepTaxiProcessingTimesNanos = new HashMap<>();

    if (currentStepIndividualClientDecisionTimesNanos != null)
      currentStepIndividualClientDecisionTimesNanos.clear();
    else currentStepIndividualClientDecisionTimesNanos = new ArrayList<>();
  }

  protected void resetOverallTimer() {
    step_ActualWallClockTimesNanos.clear();
    step_SimulatedRqsBottleneckTimesNanos.clear();
    step_SimulatedTaxiBottleneckTimesNanos.clear();
    step_SimulatedClientDecisionBottleneckTimesNanos.clear();
    step_SimulatedTotalParallelTimesNanos.clear();
    resetCurrentStepHelpers();
  }

  // --- Logging ---
  protected void logLastStepTimes(long currentSimTime) {
    log.info(
        """
            Times for Simulation Step {}:
            Actual Wall-Clock Time         : {} ns
            Simulated RQS Bottleneck       : {} ns
            Simulated Taxi Bottleneck      : {} ns
            Simulated Client Dec. Bottleneck: {} ns
            Simulated Total Parallel Time  : {} ns""",
        currentSimTime,
        getLastFromList(step_ActualWallClockTimesNanos),
        getLastFromList(step_SimulatedRqsBottleneckTimesNanos),
        getLastFromList(step_SimulatedTaxiBottleneckTimesNanos),
        getLastFromList(step_SimulatedClientDecisionBottleneckTimesNanos),
        getLastFromList(step_SimulatedTotalParallelTimesNanos));
  }

  private String getLastFromList(List<Long> list) {
    return list.isEmpty() ? "N/A" : String.valueOf(list.getLast());
  }

  protected void logTotalAggregatedTimes() {
    log.info(
        """
            Total Times:
            Actual Wall-Clock Time         : {} ns
            Simulated RQS Bottleneck       : {} ns
            Simulated Taxi Bottleneck      : {} ns
            Simulated Client Dec. Bottleneck: {} ns
            Simulated Total Parallel Time  : {} ns""",
        step_ActualWallClockTimesNanos.stream().mapToLong(Long::longValue).sum(),
        step_SimulatedRqsBottleneckTimesNanos.stream().mapToLong(Long::longValue).sum(),
        step_SimulatedTaxiBottleneckTimesNanos.stream().mapToLong(Long::longValue).sum(),
        step_SimulatedClientDecisionBottleneckTimesNanos.stream().mapToLong(Long::longValue).sum(),
        step_SimulatedTotalParallelTimesNanos.stream().mapToLong(Long::longValue).sum());
  }

  public Long getLastSimulatedTotalParallelTimeNanos() {
    if (step_SimulatedTotalParallelTimesNanos.isEmpty()) {
      return 0L;
    }
    return step_SimulatedTotalParallelTimesNanos.getLast();
  }
}
