package de.sikeller.aqs.p2p.service.strategy;

import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;

public class NearestVehicleRequestSelectionStrategy implements VehicleRequestSelectionStrategy {
  public static final String KEY = "nearest";

  private static final Comparator<VehicleRequestCandidate> ORDER =
      Comparator.comparingDouble(VehicleRequestCandidate::distanceToVehicle)
          .thenComparingLong(VehicleRequestCandidate::firstSeenAtTick)
          .thenComparing(VehicleRequestCandidate::requestId);

  @Override
  public String key() {
    return KEY;
  }

  @Override
  public Optional<VehicleRequestCandidate> select(Collection<VehicleRequestCandidate> candidates) {
    if (candidates == null || candidates.isEmpty()) {
      return Optional.empty();
    }
    return candidates.stream().sorted(ORDER).findFirst();
  }
}

