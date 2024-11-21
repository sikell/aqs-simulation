package de.sikeller.aqs.runner;

import de.sikeller.aqs.model.World;
import de.sikeller.aqs.model.events.EventDispatcher;
import de.sikeller.aqs.runner.stats.StatsCollector;
import de.sikeller.aqs.taxi.algorithm.TaxiAlgorithmSimple;
import de.sikeller.aqs.visualization.ResultVisualization;
import de.sikeller.aqs.visualization.SimulationVisualization;

public class Main {
  public static void main(String[] args) {
    var world = World.builder().maxX(800).maxY(800).build();
    var simulationVisualization = new SimulationVisualization(world);
    var resultVisualization = new ResultVisualization();
    var algorithm = new TaxiAlgorithmSimple();
    var runner = new Runner(world, algorithm, simulationVisualization);
    var statsCollector = new StatsCollector();
    var eventDispatcher = EventDispatcher.instance();

    runner.init(20, 3);
    runner.print();
    runner.run(10);
    runner.print();
    eventDispatcher.print();
    statsCollector.collect(eventDispatcher, world);
    statsCollector.print();
    resultVisualization.showResults(statsCollector.tableResults());
  }
}
