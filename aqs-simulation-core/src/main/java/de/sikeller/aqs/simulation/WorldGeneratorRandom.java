package de.sikeller.aqs.simulation;

import de.sikeller.aqs.model.*;

import java.util.Map;
import java.util.Random;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class WorldGeneratorRandom implements WorldGenerator {
  @Override
  public void init(WorldObject world, Map<String, Integer> parameters) {
    log.info("Initialize world with {}", parameters);
    int seed = parameters.getOrDefault("worldSeed", 1);
    var random = new Random(seed);

    world.reset();
    generateClients(world, parameters, random);
    generateTaxis(world, parameters, random);
  }

  private void generateTaxis(WorldObject world, Map<String, Integer> parameters, Random random) {
    var taxiCount = parameters.get("taxiCount");
    var taxiSeatCount = parameters.getOrDefault("taxiSeatCount", 2);
    var taxiSpeed = parameters.getOrDefault("taxiSpeed", 50);

    for (int i = 0; i < taxiCount; i++) {
      Position taxiPosition = randomPosition(world, random);
      world.addTaxi("t" + i, taxiSeatCount, taxiPosition, taxiSpeed);
    }
  }

  private void generateClients(WorldObject world, Map<String, Integer> parameters, Random random) {
    var clientCount = parameters.get("clientCount");
    var clientSpawnWindow = parameters.getOrDefault("clientSpawnWindow", 0);
    var clientSpeed = parameters.getOrDefault("clientSpeed", 5);

    for (int i = 0; i < clientCount; i++) {
      world.addClient(
          "c" + i,
          randomSpawnTime(clientSpawnWindow, random),
          randomPosition(world, random),
          randomPosition(world, random),
          clientSpeed);
    }
  }

  private static int randomSpawnTime(Integer clientSpawnTime, Random random) {
    return clientSpawnTime == 0 ? 0 : random.nextInt(clientSpawnTime);
  }

  private Position randomPosition(World world, Random random) {
    return new Position(random.nextInt(world.getMaxX()), random.nextInt(world.getMaxY()));
  }
}
