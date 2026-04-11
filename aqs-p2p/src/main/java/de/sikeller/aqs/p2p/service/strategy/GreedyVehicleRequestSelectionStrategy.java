package de.sikeller.aqs.p2p.service.strategy;

import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;

public class GreedyVehicleRequestSelectionStrategy implements VehicleRequestSelectionStrategy {
  public static final String KEY = "greedy";

  private static final Comparator<VehicleRequestCandidate> ORDER =
      Comparator.comparingLong(VehicleRequestCandidate::firstSeenAtTick)
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

