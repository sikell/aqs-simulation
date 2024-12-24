package de.sikeller.aqs.runner;

import de.sikeller.aqs.model.World;
import de.sikeller.aqs.model.events.EventDispatcher;
import de.sikeller.aqs.simulation.SimulationRunner;
import de.sikeller.aqs.simulation.stats.StatsCollector;
import de.sikeller.aqs.taxi.algorithm.TaxiAlgorithmFillAllSeats;
import de.sikeller.aqs.visualization.ResultVisualization;
import de.sikeller.aqs.visualization.SimulationVisualization;

public class Main {


  public static void main(String[] args) {
    var world = World.builder().maxX(800).maxY(800).build();
    var statsCollector = new StatsCollector();
    var algorithm = new TaxiAlgorithmFillAllSeats();
    var eventDispatcher = EventDispatcher.instance();

    var runner = new SimulationRunner(world, algorithm);
    var visualisation = new SimulationVisualization(world, runner);

    runner.registerObserver(visualisation);

    runner.print();
    runner.run(10);
    runner.print();

    eventDispatcher.print();
    statsCollector.collect(eventDispatcher, world);
    statsCollector.print();

    var resultVisualization = new ResultVisualization();
    resultVisualization.showResults(statsCollector.tableResults());
  }

}
