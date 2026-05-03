package de.sikeller.aqs.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WorldObjectBoundsTest {

  @Test
  void addClientClampsPositionAndTargetToWorldBounds() {
    WorldObject world = WorldObject.builder().maxX(10).maxY(20).build();

    world.addClient("c1", 0, new Position(-5, 25), new Position(15, -2), 5);

    Client client = world.getClients().iterator().next();
    assertEquals(new Position(0, 19), client.getPosition());
    assertEquals(new Position(9, 0), client.getTarget());
  }

  @Test
  void addTaxiClampsPositionToWorldBounds() {
    WorldObject world = WorldObject.builder().maxX(10).maxY(20).build();

    world.addTaxi("t1", 2, new Position(99, -1), 80);

    Taxi taxi = world.getTaxis().iterator().next();
    assertEquals(new Position(9, 0), taxi.getPosition());
  }
}

