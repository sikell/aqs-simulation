package de.sikeller.aqs.model;

import lombok.Getter;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

@Getter
public class SimulationConfiguration {

  private final Set<AlgorithmParameter> parameters;

  public SimulationConfiguration(AlgorithmParameter... parameters) {
    this.parameters = new HashSet<>();
      this.parameters.addAll(Arrays.asList(parameters));
  }

}
