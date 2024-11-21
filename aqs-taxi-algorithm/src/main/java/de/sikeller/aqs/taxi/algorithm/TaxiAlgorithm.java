package de.sikeller.aqs.taxi.algorithm;

import de.sikeller.aqs.model.World;
import de.sikeller.aqs.taxi.algorithm.model.AlgorithmResult;

public interface TaxiAlgorithm {
  default void init(World world) {}

  AlgorithmResult nextStep(World world);
}
