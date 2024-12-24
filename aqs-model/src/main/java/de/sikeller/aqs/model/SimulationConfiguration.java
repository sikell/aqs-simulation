package de.sikeller.aqs.model;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class SimulationConfiguration {

  private Set<String> parameters;

  public SimulationConfiguration(String... parameters) {
    this.parameters = new HashSet<>();
      this.parameters.addAll(Arrays.asList(parameters));
  }
}
