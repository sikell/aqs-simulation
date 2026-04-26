package de.sikeller.aqs.p2p.service.strategy;

import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class VehicleRequestSelectionStrategies {
  private static final String DEFAULT_KEY = NearestVehicleRequestSelectionStrategy.KEY;

  private static final Map<String, VehicleRequestSelectionStrategy> BY_KEY =
      Stream.of(
              new GreedyVehicleRequestSelectionStrategy(),
              new NearestVehicleRequestSelectionStrategy())
          .collect(
              Collectors.toUnmodifiableMap(
                  s -> s.key().trim().toLowerCase(Locale.ROOT), s -> s));

  public static VehicleRequestSelectionStrategy resolve(String key) {
    if (key == null || key.isBlank()) {
      return BY_KEY.get(DEFAULT_KEY);
    }
    VehicleRequestSelectionStrategy strategy = BY_KEY.get(key.trim().toLowerCase(Locale.ROOT));
    return strategy != null ? strategy : BY_KEY.get(DEFAULT_KEY);
  }
}
