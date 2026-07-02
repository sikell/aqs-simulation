package de.sikeller.aqs.model;

/** Captures per-tick metrics for time-series analysis. */
public record TickDataPoint(
    long tick,
    long calculationTimeNanos,
    int activeClientCount,
    int servedRequestCount,
    long waitingTimeSum,
    int waitingTimeCount,
    int finishedRequestCount) {}

