package de.sikeller.aqs.model;

/**
 * Captures per-tick metrics for time-series analysis: calculation time and active client count.
 */
public record TickDataPoint(long tick, long calculationTimeNanos, int activeClientCount) {}

