package de.sikeller.aqs.model;

import java.util.Map;

/** Interface defines Implementation for various taxi algorithms within the simulation. */
public interface TaxiAlgorithm {
  SimulationConfiguration getParameters();

  /**
   * Initializes the algorithm with the given world.
   *
   * @param world The world to initialize the algorithm with.
   */
  default void init(World world) {}

  /**
   * Allows algorithms to adjust initial world parameters before world generation.
   * Default: unchanged.
   */
  default Map<String, Integer> prepareWorldParameters(Map<String, Integer> parameters) {
    return parameters;
  }

  /**
   * Called before replacing the current algorithm to release resources.
   * Default: no action.
   */
  default void shutdown() {}

  /**
   * Triggers the algorithm for the calculation of the next Time step within the simulation.
   *
   * @param world The current state of the simulation world.
   * @return The result of the algorithm execution.
   */
  AlgorithmResult nextStep(World world);

  /**
   * Receives parameters from the simulation UI.
   *
   * @param parameters A map containing parameter names and their integer values.
   */
  void setParameters(Map<String, Integer> parameters);

  String getName();
}
