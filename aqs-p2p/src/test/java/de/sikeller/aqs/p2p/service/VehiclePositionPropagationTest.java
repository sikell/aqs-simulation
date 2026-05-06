package de.sikeller.aqs.p2p.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.sikeller.aqs.model.Position;
import de.sikeller.aqs.p2p.transport.inmemory.InMemoryP2PNetwork;
import org.junit.jupiter.api.Test;

class VehiclePositionPropagationTest {

  @Test
  void vehiclePublishesPosition_andClientSeesIt() {
    var network = new InMemoryP2PNetwork();
    VehicleP2PService vehicle = new VehicleP2PService("vehicle-42", network);
    ClientP2PService client = new ClientP2PService("collector-1", network);
    try {
      client.start();
      vehicle.start();

      // publish a position at tick 5
      vehicle.setSimulationState(true, 123, 456, 5L);

      // client should observe the vehicle position via snapshot
      var snapshot = client.vehiclePositionSnapshot();
      Position pos = snapshot.get("vehicle-42");
      assertNotNull(pos, "Expected client to see published vehicle position");
      assertEquals(123, pos.getX());
      assertEquals(456, pos.getY());
      assertEquals(5L, pos.getTick());
    } finally {
      vehicle.stop();
      client.stop();
    }
  }
}

