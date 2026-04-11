package de.sikeller.aqs.p2p.service.strategy;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class VehicleRequestSelectionStrategies {
  private static final String DEFAULT_KEY = NearestVehicleRequestSelectionStrategy.KEY;

  private static final Map<String, VehicleRequestSelectionStrategy> BY_KEY;

  static {
    Map<String, VehicleRequestSelectionStrategy> map = new LinkedHashMap<>();
    register(map, new GreedyVehicleRequestSelectionStrategy());
    register(map, new NearestVehicleRequestSelectionStrategy());
    BY_KEY = Map.copyOf(map);
  }

  private VehicleRequestSelectionStrategies() {}

  public static VehicleRequestSelectionStrategy resolve(String key) {
    if (key == null || key.isBlank()) {
      return BY_KEY.get(DEFAULT_KEY);
    }
    VehicleRequestSelectionStrategy strategy = BY_KEY.get(key.trim().toLowerCase(Locale.ROOT));
    return strategy != null ? strategy : BY_KEY.get(DEFAULT_KEY);
  }

  private static void register(
      Map<String, VehicleRequestSelectionStrategy> target, VehicleRequestSelectionStrategy strategy) {
    target.put(strategy.key().trim().toLowerCase(Locale.ROOT), strategy);
  }
}

