package de.sikeller.aqs.simulation;

import de.sikeller.aqs.model.World;
import java.util.Map;

public interface WorldGenerator {
  public void init(World world, Map<String, Integer> parameters);
}
