package de.sikeller.aqs.model;

/** Captures per-tick metrics for time-series analysis. */
public record TickDataPoint(
    long tick,
    long calculationTimeNanos,
    int activeClientCount,
    int servedRequestCount,
    long waitingTimeSum,
    int waitingTimeCount,
    int finishedRequestCount) {
  public static int bucketTicks() {
    return Math.max(
        1,
        Integer.getInteger(
            "aqs.simulation.tickSeriesBucketTicks",
            Integer.getInteger("aqs.massRun.timeSeriesBucketTicks", 1000)));
  }
}

