package de.sikeller.aqs.p2p.service.strategy;

import java.util.Map;

/**
 * Immutable input for vehicle-side request selection strategies.
 */
public record VehicleRequestCandidate(
    String requestId,
    long firstSeenAtTick,
    double distanceToVehicle,
    Map<String, String> payload) {}

