package de.sikeller.aqs.runner;

import de.sikeller.aqs.model.Algorithm;
import de.sikeller.aqs.model.WorldObject;
import de.sikeller.aqs.simulation.SimulationRunner;
import de.sikeller.aqs.simulation.WorldGeneratorScenario;
import de.sikeller.aqs.taxi.algorithm.collector.TaxiAlgorithmP2PCollector;
import de.sikeller.aqs.visualization.SimulationVisualization;

public class Main {

  public static void main(String[] args) {
    var world = WorldObject.builder().build();

    var defaultAlgorithm = new TaxiAlgorithmP2PCollector();
    var algorithm = new Algorithm(defaultAlgorithm);

    var worldGenerator = new WorldGeneratorScenario();

    var runner = new SimulationRunner(world, algorithm, worldGenerator);
    var visualisation = new SimulationVisualization(world, runner);
    visualisation.start();

    runner.registerObserver(visualisation);
    do {
      runner.run();
    } while (true);
  }
}
