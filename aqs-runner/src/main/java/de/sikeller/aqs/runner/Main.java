package de.sikeller.aqs.runner;

import de.sikeller.aqs.model.World;
import de.sikeller.aqs.taxi.algorithm.TaxiAlgorithmSimple;
import de.sikeller.aqs.visualization.VisualizationRunner;

public class Main {
  public static void main(String[] args) {
    var world = World.builder().maxX(400).maxY(400).build();
    var visu = new VisualizationRunner(world);
    var algorithm = new TaxiAlgorithmSimple();
    var runner = new Runner(world, algorithm, visu);
    runner.init(20, 5);
    runner.print();
    runner.run(10);
    runner.print();
  }
}
