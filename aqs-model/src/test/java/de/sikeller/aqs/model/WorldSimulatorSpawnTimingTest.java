package de.sikeller.aqs.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class WorldSimulatorSpawnTimingTest {

  @Test
  void lateSpawnedWaitingClientMovesOnlyOneTickOnFirstVisibleStep() {
    WorldObject world = WorldObject.builder().size(World.size(1000, 1000)).build();
    world.addClient("c1", 100, new Position(0, 0), new Position(100, 0), 36);

    WorldSimulator simulator = new WorldSimulator(world, EntitySimulator.defaultInstance());

    simulator.move(100); // not spawned yet because currentTime > spawnTime is required
    Client beforeSpawn = world.getClients().iterator().next();
    assertEquals(new Position(0, 0), beforeSpawn.getPosition());

    simulator.move(101); // first tick where client is spawned
    Client client = world.getClients().iterator().next();

    // 36 km/h = 10 m/s -> at first visible tick client should only move 10m, not jump from t=0.
    assertEquals(new Position(10, 0), client.getPosition());
    assertNotEquals(ClientMode.FINISHED, client.getMode());
  }
}

