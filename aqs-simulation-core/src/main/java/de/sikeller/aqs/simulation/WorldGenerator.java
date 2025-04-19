package de.sikeller.aqs.simulation;

import de.sikeller.aqs.model.WorldObject;

import java.util.Map;

public interface WorldGenerator {
  public void init(WorldObject world, Map<String, Integer> parameters);
}
