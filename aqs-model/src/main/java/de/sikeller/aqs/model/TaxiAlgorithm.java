package de.sikeller.aqs.model;


import java.util.Map;

public interface TaxiAlgorithm {
  SimulationConfiguration getParameters();

  default void init(World world) {}

  AlgorithmResult nextStep(World world);

  default void setParameters(Map<String, Integer> parameters) {}

}
