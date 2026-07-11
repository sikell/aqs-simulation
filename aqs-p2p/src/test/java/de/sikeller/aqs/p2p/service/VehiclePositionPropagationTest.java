package de.sikeller.aqs.p2p.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.sikeller.aqs.model.Position;
import de.sikeller.aqs.p2p.api.P2PPayloadKeys;
import de.sikeller.aqs.p2p.api.P2PSystemProperties;
import de.sikeller.aqs.p2p.api.P2PTopics;
import de.sikeller.aqs.p2p.transport.inmemory.InMemoryP2PNetwork;
import java.util.Map;
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

  @Test
  void collectorPushedStateSetsVehiclePosition() {
    var network = new InMemoryP2PNetwork();
    try (var client = new ClientP2PService("collector-1", network);
        var vehicle = new VehicleP2PService("vehicle-42", network)) {
      client.start();
      vehicle.start();

      client.sendVehicleState("vehicle-42", true, 0, 0, 5L, 2_000, 2_000, "BASELINE");
      String requestId =
          client.requestRide(
              "(1000,1000)",
              "(1100,1100)",
              node -> node.id().equals("vehicle-42"),
              0,
              Map.of("requestX", "1000", "requestY", "1000", "searchRadius", "100"));

      long commits =
          client.inboxSnapshot().stream()
              .filter(message -> P2PTopics.RIDE_COMMIT.equals(message.topic()))
              .filter(message -> requestId.equals(message.requestId()))
              .count();
      assertEquals(0, commits);
    }
  }

  @Test
  void assignedRidePublishesRoamingSnapshotFromVehicle() {
    var network = new InMemoryP2PNetwork();
    try (var client = new ClientP2PService("collector-1", network);
        var vehicle = new VehicleP2PService("vehicle-42", network)) {
      client.start();
      vehicle.start();

      client.announceWinner("request-1", "vehicle-42", 10, 20);

      var message =
          client.inboxSnapshot().stream()
              .filter(entry -> P2PTopics.VEHICLE_ROAMING.equals(entry.topic()))
              .findFirst()
              .orElseThrow();
      Map<String, String> payload = KeyValuePayload.parse(message.payload());
      assertEquals("10", payload.get(P2PPayloadKeys.HQ_X));
      assertEquals("20", payload.get(P2PPayloadKeys.HQ_Y));
    }
  }

  @Test
  void collectorPushedStateUpdatesVehicleP2PConfig() {
    String previous = System.getProperty(P2PSystemProperties.OVERLAY_SHORTCUTS);
    var network = new InMemoryP2PNetwork();
    try (var client = new ClientP2PService("collector-1", network);
        var vehicle = new VehicleP2PService("vehicle-42", network)) {
      System.setProperty(P2PSystemProperties.OVERLAY_SHORTCUTS, "1");
      client.start();
      vehicle.start();

      client.sendVehicleState(
          "vehicle-42",
          true,
          0,
          0,
          5L,
          2_000,
          2_000,
          "BASELINE",
          Map.of(P2PSystemProperties.OVERLAY_SHORTCUTS, "0"));

      assertEquals("0", System.getProperty(P2PSystemProperties.OVERLAY_SHORTCUTS));
    } finally {
      if (previous == null) {
        System.clearProperty(P2PSystemProperties.OVERLAY_SHORTCUTS);
      } else {
        System.setProperty(P2PSystemProperties.OVERLAY_SHORTCUTS, previous);
      }
    }
  }
}

