package de.sikeller.aqs.model;

import java.util.Map;
import java.util.Set;

public interface SimulationControl {

  void start();

  void stop();

  void init(Map<String, Integer> parameters);

  SimulationConfiguration getSimulationParameters();

}
