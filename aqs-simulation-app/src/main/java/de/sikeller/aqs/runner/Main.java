package de.sikeller.aqs.runner;

import de.sikeller.aqs.model.Algorithm;
import de.sikeller.aqs.model.WorldObject;
import de.sikeller.aqs.simulation.SimulationRunner;
import de.sikeller.aqs.simulation.WorldGeneratorRandom;
import de.sikeller.aqs.taxi.algorithm.collector.TaxiAlgorithmP2PCollector;
import de.sikeller.aqs.visualization.SimulationVisualization;
import java.util.Arrays;

public class Main {

  public static void main(String[] args) {
    if (args != null && args.length > 0 && "--mass".equalsIgnoreCase(args[0])) {
      MassRunMain.main(Arrays.copyOfRange(args, 1, args.length));
      return;
    }

    var world = WorldObject.builder().maxX(40000).maxY(40000).build();

    var algorithm = new Algorithm(new TaxiAlgorithmP2PCollector());

    var runner = new SimulationRunner(world, algorithm, new WorldGeneratorRandom());
    var visualisation = new SimulationVisualization(world, runner);
    visualisation.start();

    runner.registerObserver(visualisation);
    do {
      runner.run();
    } while (true);
  }
}
