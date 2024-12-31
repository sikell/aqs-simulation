package de.sikeller.aqs.runner;

import com.sun.jdi.InterfaceType;
import de.sikeller.aqs.model.Taxi;
import de.sikeller.aqs.model.World;
import de.sikeller.aqs.model.events.EventDispatcher;
import de.sikeller.aqs.simulation.SimulationRunner;
import de.sikeller.aqs.simulation.stats.StatsCollector;
import de.sikeller.aqs.taxi.algorithm.TaxiAlgorithm;
import de.sikeller.aqs.taxi.algorithm.TaxiAlgorithmFillAllSeats;
import de.sikeller.aqs.visualization.ResultVisualization;
import de.sikeller.aqs.visualization.SimulationVisualization;
import org.reflections.Reflections;
import org.reflections.scanners.SubTypesScanner;
import org.reflections.util.FilterBuilder;

import java.lang.reflect.Modifier;
import java.util.*;

public class Main {


  public static void main(String[] args) {
    var world = World.builder().maxX(800).maxY(800).build();

    var algorithm = new TaxiAlgorithmFillAllSeats();

    var runner = new SimulationRunner(world, algorithm);
    var visualisation = new SimulationVisualization(world, runner);

    runner.registerObserver(visualisation);

    runner.run(10);

    List<String> taxiAlgorithms = new ArrayList<>();

  }

}
