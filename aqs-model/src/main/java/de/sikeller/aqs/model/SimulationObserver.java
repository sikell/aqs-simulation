package de.sikeller.aqs.model;

public interface SimulationObserver {
  /**
   * Inform all listeners about a world update. This can be soft updates (not important) or forced
   * updates (e.g. the final state) which should be considered very important.
   *
   * @param world the new world state
   * @param forceUpdate true if this is an important update, false if this update can be skipped
   *     safely
   */
  void onUpdate(WorldObject world, boolean forceUpdate);
}
