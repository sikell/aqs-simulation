package de.sikeller.aqs.runner;

import de.sikeller.aqs.model.Algorithm;
import de.sikeller.aqs.model.WorldObject;
import de.sikeller.aqs.simulation.SimulationRunner;
import de.sikeller.aqs.simulation.WorldGeneratorRandom;
import de.sikeller.aqs.taxi.algorithm.TaxiAlgorithmSinglePassenger;
import de.sikeller.aqs.visualization.SimulationVisualization;

public class Main {

  public static void main(String[] args) {
    var world = WorldObject.builder().maxX(40000).maxY(40000).build();

    var algorithm = new Algorithm(new TaxiAlgorithmSinglePassenger());

    var runner = new SimulationRunner(world, algorithm, new WorldGeneratorRandom());
    var visualisation = new SimulationVisualization(world, runner);
    visualisation.start();

    runner.registerObserver(visualisation);
    do {
      runner.run();
    } while (true);
  }
}
