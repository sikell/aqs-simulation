package de.sikeller.aqs.p2p;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.sikeller.aqs.p2p.api.P2PMessage;
import de.sikeller.aqs.p2p.service.ClientP2PService;
import de.sikeller.aqs.p2p.service.KeyValuePayload;
import de.sikeller.aqs.p2p.service.VehicleP2PService;
import de.sikeller.aqs.p2p.transport.inmemory.InMemoryP2PNetwork;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InMemoryP2PNetworkTest {

  @Test
  void clientRequestIsVisibleToVehicleAndOfferToClient() {
    var network = new InMemoryP2PNetwork();

    try (var client = new ClientP2PService("client-1", network);
        var vehicle = new VehicleP2PService("vehicle-1", network)) {
      client.start();
      vehicle.start();

      String requestId = client.requestRide("(0,0)", "(100,100)");
      vehicle.sendOffer("client-1", "etaSeconds=90");

      assertTrue(
          vehicle.inboxSnapshot().stream()
              .anyMatch(msg -> msg.topic().equals(ClientP2PService.TOPIC_RIDE_REQUEST)));
      assertTrue(
          client.inboxSnapshot().stream()
              .anyMatch(msg -> msg.topic().equals(VehicleP2PService.TOPIC_RIDE_OFFER)));
      assertTrue(
          vehicle.inboxSnapshot().stream()
              .anyMatch(msg -> msg.requestId() != null && !msg.requestId().isBlank()));
      assertEquals(
          requestId,
          vehicle.inboxSnapshot().stream()
              .filter(msg -> msg.topic().equals(ClientP2PService.TOPIC_RIDE_REQUEST))
              .findFirst()
              .map(P2PMessage::requestId)
              .orElseThrow());
    }
  }

  @Test
  void acceptCreatesSingleCommitAndIsIdempotent() {
    var network = new InMemoryP2PNetwork();

    try (var client = new ClientP2PService("client-1", network);
        var vehicle = new VehicleP2PService("vehicle-1", network)) {
      client.start();
      vehicle.start();

      String requestId = client.requestRide("(0,0)", "(100,100)");

      List<P2PMessage> offers =
          client.inboxSnapshot().stream()
              .filter(msg -> msg.topic().equals(VehicleP2PService.TOPIC_RIDE_OFFER))
              .toList();
      assertFalse(offers.isEmpty());

      String vehicleNodeId =
          KeyValuePayload.parse(offers.getFirst().payload()).getOrDefault("vehicle", "vehicle-1");

      client.acceptOffer(vehicleNodeId, requestId);
      client.acceptOffer(vehicleNodeId, requestId);

      long commits =
          client.inboxSnapshot().stream()
              .filter(msg -> msg.topic().equals(VehicleP2PService.TOPIC_RIDE_COMMIT))
              .filter(msg -> msg.requestId().equals(requestId))
              .count();
      assertEquals(1, commits);

      assertTrue(
          client.inboxSnapshot().stream()
              .filter(msg -> msg.topic().equals(VehicleP2PService.TOPIC_RIDE_COMMIT))
              .allMatch(msg -> msg.correlationId().equals(msg.requestId())));
    }
  }

  @Test
  void producerRideRequestBypassesNeighborCap() {
    String property = "aqs.p2p.overlay.maxNeighbors";
    String previous = System.getProperty(property);
    System.setProperty(property, "1");
    try {
      var network = new InMemoryP2PNetwork();

      try (var client = new ClientP2PService("client-1", network);
          var vehicleA = new VehicleP2PService("vehicle-1", network);
          var vehicleB = new VehicleP2PService("vehicle-2", network);
          var vehicleC = new VehicleP2PService("vehicle-3", network)) {
        client.start();
        vehicleA.start();
        vehicleB.start();
        vehicleC.start();

        client.requestRide("(0,0)", "(100,100)", node -> node.id().startsWith("vehicle-"), 0, "");

        long offers =
            client.inboxSnapshot().stream()
                .filter(msg -> msg.topic().equals(VehicleP2PService.TOPIC_RIDE_OFFER))
                .count();
        assertEquals(3, offers);
      }
    } finally {
      if (previous == null) {
        System.clearProperty(property);
      } else {
        System.setProperty(property, previous);
      }
    }
  }

  @Test
  void collectorIsPinnedAsNeighborForAllNodesEvenWithNeighborCap() {
    String maxNeighborsProperty = "aqs.p2p.overlay.maxNeighbors";
    String collectorNodeProperty = "aqs.p2p.overlay.collectorNodeId";
    String previousMaxNeighbors = System.getProperty(maxNeighborsProperty);
    String previousCollectorNode = System.getProperty(collectorNodeProperty);
    System.setProperty(maxNeighborsProperty, "1");
    System.setProperty(collectorNodeProperty, "main-app-collector");
    try {
      var network = new InMemoryP2PNetwork();

      try (var collector = new ClientP2PService("main-app-collector", network);
          var vehicleA = new VehicleP2PService("vehicle-1", network);
          var vehicleB = new VehicleP2PService("vehicle-2", network);
          var vehicleC = new VehicleP2PService("vehicle-3", network)) {
        collector.start();
        vehicleA.start();
        vehicleB.start();
        vehicleC.start();

        assertTrue(vehicleA.overlayNeighborIdsSnapshot().contains("main-app-collector"));
        assertTrue(vehicleB.overlayNeighborIdsSnapshot().contains("main-app-collector"));
        assertTrue(vehicleC.overlayNeighborIdsSnapshot().contains("main-app-collector"));

        assertEquals(
            3,
            collector.overlayNeighborIdsSnapshot().stream()
                .filter(nodeId -> nodeId.startsWith("vehicle-"))
                .count());
      }
    } finally {
      if (previousMaxNeighbors == null) {
        System.clearProperty(maxNeighborsProperty);
      } else {
        System.setProperty(maxNeighborsProperty, previousMaxNeighbors);
      }
      if (previousCollectorNode == null) {
        System.clearProperty(collectorNodeProperty);
      } else {
        System.setProperty(collectorNodeProperty, previousCollectorNode);
      }
    }
  }

  @Test
  void vehicleRetriggersOfferWhenBecomingAvailableWithoutRepublish() {
    var network = new InMemoryP2PNetwork();

    try (var client = new ClientP2PService("client-1", network);
        var vehicle = new VehicleP2PService("vehicle-1", network)) {
      client.start();
      vehicle.start();

      vehicle.setSimulationState(false, 100, 100);
      String requestId =
          client.requestRide(
              "(0,0)",
              "(100,100)",
              node -> node.id().startsWith("vehicle-"),
              0,
              "",
              Map.of("requestX", "100", "requestY", "100", "searchRadius", "300"));

      long offersWhileBusy =
          client.inboxSnapshot().stream()
              .filter(msg -> msg.topic().equals(VehicleP2PService.TOPIC_RIDE_OFFER))
              .filter(msg -> requestId.equals(msg.requestId()))
              .count();
      assertEquals(0, offersWhileBusy);

      vehicle.setSimulationState(true, 100, 100);

      long offersAfterAvailable =
          client.inboxSnapshot().stream()
              .filter(msg -> msg.topic().equals(VehicleP2PService.TOPIC_RIDE_OFFER))
              .filter(msg -> requestId.equals(msg.requestId()))
              .count();
      assertEquals(1, offersAfterAvailable);
    }
  }
}

