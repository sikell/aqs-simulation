package de.sikeller.aqs.p2p;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.sikeller.aqs.p2p.api.P2PMessage;
import de.sikeller.aqs.p2p.api.P2PSystemProperties;
import de.sikeller.aqs.p2p.api.P2PTopics;
import de.sikeller.aqs.p2p.service.ClientP2PService;
import de.sikeller.aqs.p2p.service.VehicleP2PService;
import de.sikeller.aqs.p2p.transport.inmemory.InMemoryP2PNetwork;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InMemoryP2PNetworkTest {

  @Test
  void clientRequestIsVisibleToVehicleAndCommitToClient() {
    var network = new InMemoryP2PNetwork();

    try (var client = new ClientP2PService("client-1", network);
        var vehicle = new VehicleP2PService("vehicle-1", network)) {
      client.start();
      vehicle.start();

      long receivedBefore = vehicle.runtimeStatus().messagesReceived();
      String requestId = client.requestRide("(0,0)", "(100,100)");

      // Vehicle processes the request inline and sends the commit directly to the client.
      assertEquals(receivedBefore + 1, vehicle.runtimeStatus().messagesReceived());
      assertTrue(
          client.inboxSnapshot().stream()
              .anyMatch(msg -> msg.topic().equals(P2PTopics.RIDE_COMMIT)));
      assertEquals(
          requestId,
          client.inboxSnapshot().stream()
              .filter(msg -> msg.topic().equals(P2PTopics.RIDE_COMMIT))
              .findFirst()
              .map(P2PMessage::correlationId)
              .orElseThrow());
      assertTrue(
          client.inboxSnapshot().stream()
              .filter(msg -> msg.topic().equals(P2PTopics.RIDE_COMMIT))
              .allMatch(msg -> vehicle.descriptor().id().equals(msg.senderId())));
    }
  }

  @Test
  void vehicleCommitsExactlyOncePerRequest() {
    var network = new InMemoryP2PNetwork();

    try (var client = new ClientP2PService("client-1", network);
        var vehicle = new VehicleP2PService("vehicle-1", network)) {
      client.start();
      vehicle.start();

      String requestId = client.requestRide("(0,0)", "(100,100)");

      // Vehicle autonomously commits; guard in VehicleP2PService ensures exactly one RIDE_COMMIT
      long commits =
          client.inboxSnapshot().stream()
              .filter(msg -> msg.topic().equals(P2PTopics.RIDE_COMMIT))
              .filter(msg -> msg.requestId().equals(requestId))
              .count();
      assertEquals(1, commits);

      assertTrue(
          client.inboxSnapshot().stream()
              .filter(msg -> msg.topic().equals(P2PTopics.RIDE_COMMIT))
              .allMatch(msg -> msg.correlationId().equals(msg.requestId())));
    }
  }

  @Test
  void clientReceivesCommitFromAtLeastOneVehicle() {
    var network = new InMemoryP2PNetwork();

    try (var client = new ClientP2PService("client-fcfs", network);
        var vehicleA = new VehicleP2PService("vehicle-fcfs-a", network);
        var vehicleB = new VehicleP2PService("vehicle-fcfs-b", network)) {
      client.start();
      vehicleA.start();
      vehicleB.start();

      String requestId =
          client.requestRide(
              "(0,0)",
              "(100,100)",
              node -> node.id().startsWith("vehicle-fcfs-"),
              0);

      // Both vehicles may commit directly; at least one commit must arrive
      long commits =
          client.inboxSnapshot().stream()
              .filter(msg -> msg.topic().equals(P2PTopics.RIDE_COMMIT))
              .filter(msg -> requestId.equals(msg.requestId()))
              .count();
      assertTrue(commits >= 1);
    }
  }

  @Test
  void producerRideRequestBypassesNeighborCap() {
    String property = P2PSystemProperties.OVERLAY_MIN_NEIGHBORS;
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

        client.requestRide("(0,0)", "(100,100)", node -> node.id().startsWith("vehicle-"), 0);

        // All 3 vehicles receive the request and each autonomously commits
        long commits =
            client.inboxSnapshot().stream()
                .filter(msg -> msg.topic().equals(P2PTopics.RIDE_COMMIT))
                .count();
        assertEquals(3, commits);
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
    String maxNeighborsProperty = P2PSystemProperties.OVERLAY_MIN_NEIGHBORS;
    String collectorNodeProperty = P2PSystemProperties.OVERLAY_COLLECTOR_NODE_ID;
    String pinCollectorProperty = P2PSystemProperties.OVERLAY_PIN_COLLECTOR;
    String previousMaxNeighbors = System.getProperty(maxNeighborsProperty);
    String previousCollectorNode = System.getProperty(collectorNodeProperty);
    String previousPinCollector = System.getProperty(pinCollectorProperty);
    System.setProperty(maxNeighborsProperty, "1");
    System.setProperty(collectorNodeProperty, "main-app-collector");
    System.setProperty(pinCollectorProperty, "true");
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
      if (previousPinCollector == null) {
        System.clearProperty(pinCollectorProperty);
      } else {
        System.setProperty(pinCollectorProperty, previousPinCollector);
      }
    }
  }

  @Test
  void vehicleOverlayPrefersNearestByPosition() {
    withSystemProperties(
        Map.of(
            P2PSystemProperties.OVERLAY_MIN_NEIGHBORS, "1",
            P2PSystemProperties.OVERLAY_SHORTCUTS, "0",
            P2PSystemProperties.OVERLAY_PIN_COLLECTOR, "false"),
        () -> {
          var network = new InMemoryP2PNetwork();

          try (var vehicleA = new VehicleP2PService("vehicle-a", network);
              var vehicleB = new VehicleP2PService("vehicle-b", network);
              var vehicleC = new VehicleP2PService("vehicle-c", network)) {
            vehicleA.start();
            vehicleB.start();
            vehicleC.start();

            vehicleA.setSimulationState(true, 0, 0, 1);
            vehicleB.setSimulationState(true, 10_000, 10_000, 1);
            vehicleC.setSimulationState(true, 5, 5, 1);

            Set<String> neighbors = vehicleA.overlayNeighborIdsSnapshot();
            assertTrue(neighbors.contains("vehicle-c"));
            assertFalse(neighbors.contains("vehicle-b"));
          }
        });
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
                  Map.of("requestX", "100", "requestY", "100", "searchRadius", "300"));

      long offersWhileBusy =
          client.inboxSnapshot().stream()
              .filter(msg -> msg.topic().equals(P2PTopics.RIDE_COMMIT))
              .filter(msg -> requestId.equals(msg.requestId()))
              .count();
      assertEquals(0, offersWhileBusy);

      vehicle.setSimulationState(true, 100, 100);

      long offersAfterAvailable =
          client.inboxSnapshot().stream()
              .filter(msg -> msg.topic().equals(P2PTopics.RIDE_COMMIT))
              .filter(msg -> requestId.equals(msg.requestId()))
              .count();
      assertEquals(1, offersAfterAvailable);
    }
  }

  @Test
  void vehicleDoesNotOfferOutsideSearchRadiusByDefault() {
    var network = new InMemoryP2PNetwork();

    try (var client = new ClientP2PService("client-range", network);
        var vehicle = new VehicleP2PService("vehicle-range", network)) {
      client.start();
      vehicle.start();
      vehicle.setSimulationState(true, 0, 0);

      String requestId =
          client.requestRide(
              "(1000,1000)",
              "(1100,1100)",
              node -> node.id().equals("vehicle-range"),
              0,
                  Map.of("requestX", "1000", "requestY", "1000", "searchRadius", "100"));

      long offers =
          client.inboxSnapshot().stream()
              .filter(msg -> msg.topic().equals(P2PTopics.RIDE_COMMIT))
              .filter(msg -> requestId.equals(msg.requestId()))
              .count();
      assertEquals(0, offers);
    }
  }

  @Test
  void vehicleMayOfferOutsideRangeWhenExplicitlyEnabled() {
    withSystemProperties(
        Map.of(P2PSystemProperties.VEHICLE_ALLOW_OUTSIDE_CLIENT_RANGE, "true"),
        () -> {
          var network = new InMemoryP2PNetwork();

          try (var client = new ClientP2PService("client-range-open", network);
              var vehicle = new VehicleP2PService("vehicle-range-open", network)) {
            client.start();
            vehicle.start();
            vehicle.setSimulationState(true, 0, 0);

            String requestId =
                client.requestRide(
                    "(1000,1000)",
                    "(1100,1100)",
                    node -> node.id().equals("vehicle-range-open"),
                    0,
                        Map.of("requestX", "1000", "requestY", "1000", "searchRadius", "100"));

            long offers =
                client.inboxSnapshot().stream()
                    .filter(msg -> msg.topic().equals(P2PTopics.RIDE_COMMIT))
                    .filter(msg -> requestId.equals(msg.requestId()))
                    .count();
            assertEquals(1, offers);
          }
        });
  }

  @Test
  void forwardedRequestMayOfferOutsideInitialSeedRadiusByDefault() {
    withSystemProperties(
        Map.of(
            P2PSystemProperties.OVERLAY_MIN_NEIGHBORS, "3",
            P2PSystemProperties.OVERLAY_SHORTCUTS, "0",
            P2PSystemProperties.OVERLAY_PIN_COLLECTOR, "false"),
        () -> {
          var network = new InMemoryP2PNetwork();

          try (var client = new ClientP2PService("client-forward-range", network);
              var seedVehicle = new VehicleP2PService("vehicle-seed", network);
              var neighborVehicle = new VehicleP2PService("vehicle-neighbor", network)) {
            client.start();
            seedVehicle.start();
            neighborVehicle.start();

            // Seed is busy and forwards. Neighbor is far away and would fail strict range check.
            seedVehicle.setSimulationState(false, 0, 0);
            neighborVehicle.setSimulationState(true, 1000, 1000);

            String requestId =
                client.requestRide(
                    "(0,0)",
                    "(100,100)",
                    node -> node.id().equals("vehicle-seed"),
                    1,
                        Map.of("requestX", "0", "requestY", "0", "searchRadius", "100"));

            long offers =
                client.inboxSnapshot().stream()
                    .filter(msg -> msg.topic().equals(P2PTopics.RIDE_COMMIT))
                    .filter(msg -> requestId.equals(msg.requestId()))
                    .count();
            assertEquals(1, offers);
          }
        });
  }

  @Test
  void requestForwardingRespectsTtlAcrossSparseOverlay() {
    withSystemProperties(
        Map.of(
            P2PSystemProperties.OVERLAY_MIN_NEIGHBORS, "1",
            P2PSystemProperties.OVERLAY_SHORTCUTS, "0",
            P2PSystemProperties.OVERLAY_PIN_COLLECTOR, "false"),
        () -> {
          var network = new InMemoryP2PNetwork();

          try (var client = new ClientP2PService("client-ttl", network);
              var vehicleA = new VehicleP2PService("vehicle-a", network);
              var vehicleB = new VehicleP2PService("vehicle-b", network);
              var vehicleC = new VehicleP2PService("vehicle-c", network);
              var vehicleD = new VehicleP2PService("vehicle-d", network);
              var vehicleE = new VehicleP2PService("vehicle-e", network)) {
            client.start();
            vehicleA.start();
            vehicleB.start();
            vehicleC.start();
            vehicleD.start();
            vehicleE.start();

            List<VehicleP2PService> vehicles = List.of(vehicleA, vehicleB, vehicleC, vehicleD, vehicleE);
            vehicles.forEach(vehicle -> vehicle.setSimulationState(false, 0, 0));

            Set<String> vehicleNodeIds =
                vehicles.stream()
                    .map(vehicle -> vehicle.descriptor().id())
                    .collect(java.util.stream.Collectors.toSet());
            Map<String, Set<String>> vehicleOverlayGraph = new HashMap<>();
            vehicles.forEach(
                vehicle -> {
                  Set<String> neighbors =
                      vehicle.overlayNeighborIdsSnapshot().stream()
                          .filter(vehicleNodeIds::contains)
                          .collect(java.util.stream.Collectors.toSet());
                  vehicleOverlayGraph.put(vehicle.descriptor().id(), neighbors);
                });

            String seedVehicleNodeId = selectSeedVehicle(vehicleOverlayGraph, vehicleA.descriptor().id());

            long reachedTtl0 =
                runTtlRequestAndCountReceivers(client, vehicles, seedVehicleNodeId, 0);
            long reachedTtl1 =
                runTtlRequestAndCountReceivers(client, vehicles, seedVehicleNodeId, 1);
            long reachedTtl2 =
                runTtlRequestAndCountReceivers(client, vehicles, seedVehicleNodeId, 2);

            assertEquals(expectedReachableVehicles(vehicleOverlayGraph, seedVehicleNodeId, 0), reachedTtl0);
            assertEquals(expectedReachableVehicles(vehicleOverlayGraph, seedVehicleNodeId, 1), reachedTtl1);
            assertEquals(expectedReachableVehicles(vehicleOverlayGraph, seedVehicleNodeId, 2), reachedTtl2);

            assertTrue(reachedTtl1 >= reachedTtl0);
            assertTrue(reachedTtl2 >= reachedTtl1);
          }
        });
  }

  @Test
  void firstSeenRequestIsForwardedEvenWhenSeedCanOffer() {
    withSystemProperties(
        Map.of(
            P2PSystemProperties.OVERLAY_MIN_NEIGHBORS, "2",
            P2PSystemProperties.OVERLAY_SHORTCUTS, "0",
            P2PSystemProperties.OVERLAY_PIN_COLLECTOR, "false"),
        () -> {
          var network = new InMemoryP2PNetwork();

          try (var client = new ClientP2PService("client-forward-and-offer", network);
              var seedVehicle = new VehicleP2PService("vehicle-seed-forward", network);
              var neighborVehicle = new VehicleP2PService("vehicle-neighbor-forward", network)) {
            client.start();
            seedVehicle.start();
            neighborVehicle.start();

            seedVehicle.setSimulationState(true, 0, 0);
            neighborVehicle.setSimulationState(true, 10, 10);

            long beforeNeighborMessages = neighborVehicle.runtimeStatus().messagesReceived();
            String requestId =
                client.requestRide(
                    "(0,0)",
                    "(100,100)",
                    node -> node.id().equals("vehicle-seed-forward"),
                    1,
                        Map.of("requestX", "0", "requestY", "0", "searchRadius", "200"));

            boolean seedCommittedToClient =
                client.inboxSnapshot().stream()
                    .filter(msg -> P2PTopics.RIDE_COMMIT.equals(msg.topic()))
                    .anyMatch(msg -> requestId.equals(msg.requestId()));

            assertTrue(seedCommittedToClient);
            assertTrue(neighborVehicle.runtimeStatus().messagesReceived() > beforeNeighborMessages);
          }
        });
  }

  private String selectSeedVehicle(Map<String, Set<String>> vehicleOverlayGraph, String fallback) {
    return vehicleOverlayGraph.entrySet().stream()
        .filter(entry -> entry.getValue() != null && !entry.getValue().isEmpty())
        .map(Map.Entry::getKey)
        .findFirst()
        .orElse(fallback);
  }

  private long expectedReachableVehicles(
      Map<String, Set<String>> vehicleOverlayGraph,
      String seedVehicleNodeId,
      int hops) {
    Set<String> visited = new HashSet<>();
    Set<String> frontier = new HashSet<>();
    visited.add(seedVehicleNodeId);
    frontier.add(seedVehicleNodeId);

    for (int hop = 0; hop < hops; hop++) {
      Set<String> nextFrontier = new HashSet<>();
      for (String nodeId : frontier) {
        Set<String> neighbors = vehicleOverlayGraph.getOrDefault(nodeId, Set.of());
        for (String neighbor : neighbors) {
          if (visited.add(neighbor)) {
            nextFrontier.add(neighbor);
          }
        }
      }
      if (nextFrontier.isEmpty()) {
        break;
      }
      frontier = nextFrontier;
    }
    return visited.size();
  }

  @Test
  void requestForwardingDoesNotSendBackToImmediateSender() {
    var network = new InMemoryP2PNetwork();

    try (var client = new ClientP2PService("client-forward", network);
        var vehicleA = new VehicleP2PService("vehicle-a", network);
        var vehicleB = new VehicleP2PService("vehicle-b", network);
        var vehicleC = new VehicleP2PService("vehicle-c", network)) {
      client.start();
      vehicleA.start();
      vehicleB.start();
      vehicleC.start();

      vehicleA.setSimulationState(false, 0, 0);
      vehicleB.setSimulationState(false, 0, 0);
      vehicleC.setSimulationState(false, 0, 0);

      long beforeSeedMessages = vehicleA.runtimeStatus().messagesReceived();
      client.requestRide(
          "(0,0)",
          "(100,100)",
          node -> node.id().equals("vehicle-a"),
          2);

      long requestsSeenBySeedVehicle = vehicleA.runtimeStatus().messagesReceived() - beforeSeedMessages;

      // Seed receives exactly the client-origin request and no bounce-back copy from forwarded peers.
      assertEquals(1, requestsSeenBySeedVehicle);
    }
  }

  @Test
  void kHopForwardingUsesReciprocalOverlayLinksForVehicles() {
    withSystemProperties(
        Map.of(
            P2PSystemProperties.OVERLAY_MIN_NEIGHBORS, "1",
            P2PSystemProperties.OVERLAY_SHORTCUTS, "0",
            P2PSystemProperties.OVERLAY_PIN_COLLECTOR, "false"),
        () -> {
          var network = new InMemoryP2PNetwork();

          try (var client = new ClientP2PService("client-reciprocal", network);
              var seedVehicle = new VehicleP2PService("vehicle-seed", network);
              var neighborVehicle = new VehicleP2PService("vehicle-neighbor", network);
              var idleVehicle = new VehicleP2PService("vehicle-idle", network)) {
            client.start();
            seedVehicle.start();
            neighborVehicle.start();
            idleVehicle.start();

            seedVehicle.setSimulationState(false, 0, 0);
            neighborVehicle.setSimulationState(true, 10, 10);
            idleVehicle.setSimulationState(true, 20, 20);

            String requestId =
                client.requestRide(
                    "(0,0)",
                    "(100,100)",
                    node -> node.id().equals("vehicle-seed"),
                    1,
                        Map.of("requestX", "0", "requestY", "0", "searchRadius", "50"));

            long offers =
                client.inboxSnapshot().stream()
                    .filter(msg -> msg.topic().equals(P2PTopics.RIDE_COMMIT))
                    .filter(msg -> requestId.equals(msg.requestId()))
                    .count();
            assertTrue(offers >= 1);
          }
        });
  }

  private long runTtlRequestAndCountReceivers(
      ClientP2PService client,
      List<VehicleP2PService> vehicles,
      String seedVehicleNodeId,
      int hops) {
    long[] beforeMessagesReceived =
        vehicles.stream().mapToLong(vehicle -> vehicle.runtimeStatus().messagesReceived()).toArray();

    client.requestRide(
        "(0,0)",
        "(100,100)",
        node -> node.id().equals(seedVehicleNodeId),
        hops
    );

    long reachedVehicles = 0;
    for (int i = 0; i < vehicles.size(); i++) {
      long previous = beforeMessagesReceived[i];
      long current = vehicles.get(i).runtimeStatus().messagesReceived();
      if (current > previous) {
        reachedVehicles++;
      }
    }
    return reachedVehicles;
  }


  private void withSystemProperties(Map<String, String> properties, Runnable body) {
    Map<String, String> previousValues = new HashMap<>();
    Set<String> keys = properties.keySet();
    keys.forEach(key -> previousValues.put(key, System.getProperty(key)));
    properties.forEach(System::setProperty);
    try {
      body.run();
    } finally {
      keys.forEach(
          key -> {
            String previous = previousValues.get(key);
            if (previous == null) {
              System.clearProperty(key);
            } else {
              System.setProperty(key, previous);
            }
          });
    }
  }
}

