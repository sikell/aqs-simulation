package de.sikeller.aqs.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.sikeller.aqs.model.Client;
import de.sikeller.aqs.model.Position;
import de.sikeller.aqs.model.SpawnScenario;
import de.sikeller.aqs.model.WorldObject;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorldGeneratorScenarioMapSizeTest {

  @Test
  void generatedClientsStayWithinConfiguredMapSizeForAllScenarios() {
    int mapSize = 250;
    int clientCount = 300;

    for (SpawnScenario scenario : SpawnScenario.values()) {
      WorldObject world = WorldObject.builder().maxX(1).maxY(1).build();
      Map<String, Integer> parameters = baseParameters(mapSize, clientCount);
      parameters.put("spawnScenario", scenario.ordinal());

      new WorldGeneratorScenario().init(world, parameters);

      assertEquals(mapSize, world.getMaxX(), "maxX should come from mapSize");
      assertEquals(mapSize, world.getMaxY(), "maxY should come from mapSize");
      assertEquals(clientCount, world.getClients().size(), "all clients should be created");

      for (Client client : world.getClients()) {
        assertInsideMap(client.getPosition(), mapSize, "from", scenario, client.getName());
        assertInsideMap(client.getTarget(), mapSize, "to", scenario, client.getName());
      }
    }
  }

  private static Map<String, Integer> baseParameters(int mapSize, int clientCount) {
    Map<String, Integer> parameters = new HashMap<>();
    parameters.put("worldSeed", 42);
    parameters.put("mapSize", mapSize);
    parameters.put("taxiCount", 10);
    parameters.put("taxiSeatCount", 2);
    parameters.put("taxiSpeed", 80);
    parameters.put("clientCount", clientCount);
    parameters.put("clientSpawnWindow", 1000);
    parameters.put("clientSpeed", 5);
    return parameters;
  }

  private static void assertInsideMap(
      Position position, int mapSize, String role, SpawnScenario scenario, String clientName) {
    assertTrue(
        position.getX() >= 0 && position.getX() < mapSize,
        () ->
            String.format(
                "Client %s (%s, %s) has x=%d outside [0,%d)",
                clientName, scenario.name(), role, position.getX(), mapSize));
    assertTrue(
        position.getY() >= 0 && position.getY() < mapSize,
        () ->
            String.format(
                "Client %s (%s, %s) has y=%d outside [0,%d)",
                clientName, scenario.name(), role, position.getY(), mapSize));
  }
}

