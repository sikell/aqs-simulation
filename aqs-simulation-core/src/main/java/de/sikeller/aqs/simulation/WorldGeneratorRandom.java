package de.sikeller.aqs.simulation;

import de.sikeller.aqs.model.Client;
import de.sikeller.aqs.model.Position;
import de.sikeller.aqs.model.Taxi;
import de.sikeller.aqs.model.World;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class WorldGeneratorRandom implements WorldGenerator {
  @Override
  public void init(World world, Map<String, Integer> parameters) {
    log.info("Initialize world with {}", parameters);
    world.reset();
    generateClients(world, parameters);
    generateTaxis(world, parameters);
  }

  private void generateTaxis(World world, Map<String, Integer> parameters) {
    var taxiCount = parameters.get("taxiCount");
    var taxiSeatCount = parameters.getOrDefault("taxiSeatCount", 2);
    var taxiSpeed = parameters.getOrDefault("taxiSpeed", 1);

    for (int i = 0; i < taxiCount; i++) {
      Position taxiPosition = randomPosition(world);
      world
          .getTaxis()
          .add(
              Taxi.builder()
                  .name("t" + i)
                  .capacity(taxiSeatCount)
                  .position(taxiPosition)
                  .currentSpeed(taxiSpeed)
                  .build());
    }
  }

  private void generateClients(World world, Map<String, Integer> parameters) {
    var clientCount = parameters.get("clientCount");
    var clientSpawnWindow = parameters.getOrDefault("clientSpawnWindow", 0);

    for (int i = 0; i < clientCount; i++) {
      world
          .getClients()
          .add(
              Client.builder()
                  .name("c" + i)
                  .spawnTime(randomSpawnTime(world, clientSpawnWindow))
                  .position(randomPosition(world))
                  .target(randomPosition(world))
                  .build());
    }
  }

  private static int randomSpawnTime(World world, Integer clientSpawnTime) {
    return clientSpawnTime == 0 ? 0 : world.getRandom().nextInt(clientSpawnTime);
  }

  private Position randomPosition(World world) {
    return new Position(
        world.getRandom().nextInt(world.getMaxX()), world.getRandom().nextInt(world.getMaxY()));
  }
}
