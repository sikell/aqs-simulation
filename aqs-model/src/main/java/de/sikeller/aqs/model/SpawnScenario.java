package de.sikeller.aqs.model;

/** Selectable client spawn distribution scenarios for the simulation. */
public enum SpawnScenario {
  /** S1 – Baseline: constant load throughout the day, uniform random spawn. */
  BASELINE,
  /** S2 – Rush Hour: peaks at 7-9h and 17-19h within the spawn window. */
  RUSH_HOUR,
  /** S3 – Spatial Imbalance: CBD hotspot (60 %) + weak periphery (40 %). */
  SPATIAL_IMBALANCE,
  /** S4 – Spatial Islands: multiple hotspot islands distributed across the map. */
  SPATIAL_ISLANDS;

  /** Returns the scenario for the given ordinal, defaulting to {@link #BASELINE}. */
  public static SpawnScenario fromOrdinal(int ordinal) {
    SpawnScenario[] values = values();
    if (ordinal < 0 || ordinal >= values.length) {
      return BASELINE;
    }
    return values[ordinal];
  }

  /** Case-insensitive lookup by name, defaulting to {@link #BASELINE}. */
  public static SpawnScenario fromLabel(String label) {
    if (label == null || label.isBlank()) {
      return BASELINE;
    }
    for (SpawnScenario s : values()) {
      if (s.name().equalsIgnoreCase(label.trim())) {
        return s;
      }
    }
    return BASELINE;
  }
}

