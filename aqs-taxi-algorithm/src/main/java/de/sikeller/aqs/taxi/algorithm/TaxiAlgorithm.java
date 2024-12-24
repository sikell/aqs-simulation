package de.sikeller.aqs.taxi.algorithm;

import de.sikeller.aqs.model.World;
import de.sikeller.aqs.taxi.algorithm.model.AlgorithmResult;
import lombok.Getter;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public interface TaxiAlgorithm {
  Set<String> parameters = new HashSet<>(Arrays.asList("taxiCount", "clientCount"));

  default void init(World world) {}

  AlgorithmResult nextStep(World world);

}
