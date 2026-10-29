package de.sikeller.aqs.model;

/** Captures one served request for percentile and spatial analyses. */
public record RequestDataPoint(
    String clientName,
    long spawnTime,
    long pickupTime,
    long finishTime,
    long waitingTime,
    long travelTime,
    int originX,
    int originY,
    int targetX,
    int targetY) {}
