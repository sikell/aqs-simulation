package de.sikeller.aqs.model;


public interface TaxiAlgorithm {
  SimulationConfiguration getParameters();

  default void init(World world) {}

  AlgorithmResult nextStep(World world);

}
