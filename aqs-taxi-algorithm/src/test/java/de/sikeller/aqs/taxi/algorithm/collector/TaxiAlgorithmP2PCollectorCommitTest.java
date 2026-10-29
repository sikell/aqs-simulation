package de.sikeller.aqs.taxi.algorithm.collector;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.sikeller.aqs.model.Client;
import de.sikeller.aqs.model.ClientEntity;
import de.sikeller.aqs.model.ClientMode;
import de.sikeller.aqs.model.Position;
import de.sikeller.aqs.model.Taxi;
import de.sikeller.aqs.model.TargetList;
import de.sikeller.aqs.model.World;
import de.sikeller.aqs.p2p.api.P2PMessage;
import de.sikeller.aqs.p2p.api.P2PPayloadKeys;
import de.sikeller.aqs.p2p.api.P2PSystemProperties;
import de.sikeller.aqs.p2p.api.P2PTopics;
import de.sikeller.aqs.p2p.service.KeyValuePayload;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TaxiAlgorithmP2PCollectorCommitTest {

  @Test
  void embeddedDirectCommitUsesRequestIdLookup() throws Exception {
    TaxiAlgorithmP2PCollector collector = new TaxiAlgorithmP2PCollector();
    TaxiCollectorRuntimeState state = runtimeState(collector);
    state.putPendingRequest("request-1", "client-1", 0L);

    Method method =
        TaxiAlgorithmP2PCollector.class.getDeclaredMethod(
            "handleDirectCommit", String.class, String.class, String.class);
    method.setAccessible(true);
    method.invoke(collector, "request-1", "vehicle-1", "t1");

    TaxiCollectorRuntimeState.PendingRequest pending = state.getByRequestId("request-1");
    assertTrue(pending.isCommitted());
    assertEquals("vehicle-1", pending.committedVehicleNodeId());
  }

  @Test
  void lanInboxCommitUsesMessageRequestId() throws Exception {
    TaxiAlgorithmP2PCollector collector = new TaxiAlgorithmP2PCollector();
    TaxiCollectorRuntimeState state = runtimeState(collector);
    state.putPendingRequest("request-1", "client-1", 0L);
    Map<String, String> payload =
        Map.of(P2PPayloadKeys.VEHICLE, "vehicle-1", P2PPayloadKeys.REQUEST_ID, "wrong-request");
    P2PMessage message =
        P2PMessage.now(
            "vehicle-1",
            P2PTopics.RIDE_COMMIT,
            KeyValuePayload.write(payload),
            "request-1",
            "request-1");

    Method method =
        TaxiAlgorithmP2PCollector.class.getDeclaredMethod(
            "handleIncoming", de.sikeller.aqs.model.World.class, List.class);
    method.setAccessible(true);
    method.invoke(collector, null, List.of(message));

    TaxiCollectorRuntimeState.PendingRequest pending = state.getByRequestId("request-1");
    assertTrue(pending.isCommitted());
    assertEquals("vehicle-1", pending.committedVehicleNodeId());
  }

  @Test
  void lanCommitUsesMappedTaxiNameForAssignment() {
    TaxiCollectorRuntimeState state = new TaxiCollectorRuntimeState();
    state.putPendingRequest("request-1", "client-1", 0L);
    Client client =
        ClientEntity.builder()
            .name("client-1")
            .mode(ClientMode.WAITING)
            .position(new Position(0, 0))
            .target(new Position(10, 10))
            .build();
    Taxi taxi = taxi("t0");
    AtomicReference<Taxi> assignedTaxi = new AtomicReference<>();
    CommitHandler handler =
        new CommitHandler(
            state,
            world -> Map.of("t0", taxi),
            (selectedTaxi, selectedClient, world) -> {
              assignedTaxi.set(selectedTaxi);
              assertEquals(client, selectedClient);
            },
            event -> {},
            (requestId, vehicleNodeId, selectedClient) -> {});

    handler.handleCommit(
        "vehicle-raspi-7", "t0", state.getByRequestId("request-1"), world(client));

    assertEquals("t0", assignedTaxi.get().getName());
  }

  @Test
  void topologyScanDefaultsOnForLanOnly() throws Exception {
    Method method = TaxiAlgorithmP2PCollector.class.getDeclaredMethod("isTopologyScanEnabled");
    method.setAccessible(true);

    TaxiAlgorithmP2PCollector embedded = new TaxiAlgorithmP2PCollector();
    embedded.setParameters(Map.of("p2pEmbeddedSimulation", 1));
    assertFalse((boolean) method.invoke(embedded));

    TaxiAlgorithmP2PCollector lan = new TaxiAlgorithmP2PCollector();
    lan.setParameters(Map.of("p2pEmbeddedSimulation", 0));
    assertTrue((boolean) method.invoke(lan));
  }

  @Test
  void lanHqPositionsUseVehicleRoamingTelemetry() throws Exception {
    TaxiAlgorithmP2PCollector collector = new TaxiAlgorithmP2PCollector();
    collector.setParameters(Map.of("p2pEmbeddedSimulation", 0));
    mappedVehicles(collector).put("vehicle-raspi-7", "t0");

    Method method =
        TaxiAlgorithmP2PCollector.class.getDeclaredMethod(
            "handleIncoming", de.sikeller.aqs.model.World.class, List.class);
    method.setAccessible(true);
    Map<String, String> payload = Map.of(P2PPayloadKeys.HQ_X, "10", P2PPayloadKeys.HQ_Y, "20");
    method.invoke(
        collector,
        null,
        List.of(
            P2PMessage.now(
                "vehicle-raspi-7", P2PTopics.VEHICLE_ROAMING, KeyValuePayload.write(payload))));

    assertArrayEquals(new int[] {10, 20}, collector.getPageRankHqPositions().get("t0"));
  }

  @Test
  void p2pSystemPropertiesAreRestoredOnShutdown() throws Exception {
    String previousMin = System.getProperty(P2PSystemProperties.OVERLAY_MIN_NEIGHBORS);
    String previousStrategy = System.getProperty(P2PSystemProperties.VEHICLE_OPEN_REQUEST_STRATEGY);
    System.setProperty(P2PSystemProperties.OVERLAY_MIN_NEIGHBORS, "old-min");
    System.setProperty(P2PSystemProperties.VEHICLE_OPEN_REQUEST_STRATEGY, "old-strategy");
    try {
      TaxiAlgorithmP2PCollector collector = new TaxiAlgorithmP2PCollector();
      Method applyOverlay =
          TaxiAlgorithmP2PCollector.class.getDeclaredMethod("applyOverlayConfig", Map.class);
      applyOverlay.setAccessible(true);
      applyOverlay.invoke(
          collector,
          Map.of(
              "p2pOverlayMinNeighbors", 3,
              "p2pOverlayMaxNeighbors", 4,
              "p2pOverlayShortcuts", 1,
              "p2pOverlayShortcutStrategy", 1));
      collector.setParameters(Map.of("p2pIdleTravelEnabled", 0));

      assertEquals("3", System.getProperty(P2PSystemProperties.OVERLAY_MIN_NEIGHBORS));

      collector.shutdown();

      assertEquals("old-min", System.getProperty(P2PSystemProperties.OVERLAY_MIN_NEIGHBORS));
      assertEquals(
          "old-strategy", System.getProperty(P2PSystemProperties.VEHICLE_OPEN_REQUEST_STRATEGY));
    } finally {
      restore(P2PSystemProperties.OVERLAY_MIN_NEIGHBORS, previousMin);
      restore(P2PSystemProperties.VEHICLE_OPEN_REQUEST_STRATEGY, previousStrategy);
    }
  }

  private TaxiCollectorRuntimeState runtimeState(TaxiAlgorithmP2PCollector collector)
      throws Exception {
    Field field = TaxiAlgorithmP2PCollector.class.getDeclaredField("runtimeState");
    field.setAccessible(true);
    return (TaxiCollectorRuntimeState) field.get(collector);
  }

  @SuppressWarnings("unchecked")
  private Map<String, String> mappedVehicles(TaxiAlgorithmP2PCollector collector) throws Exception {
    Field field = TaxiAlgorithmP2PCollector.class.getDeclaredField("vehicleNodeToTaxiName");
    field.setAccessible(true);
    return (Map<String, String>) field.get(collector);
  }

  private static void restore(String key, String value) {
    if (value == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, value);
    }
  }

  private static World world(Client client) {
    return (World)
        Proxy.newProxyInstance(
            World.class.getClassLoader(),
            new Class<?>[] {World.class},
            (proxy, method, args) -> switch (method.getName()) {
              case "getClients" -> List.of(client);
              case "mutate" -> proxy;
              default -> throw new UnsupportedOperationException(method.getName());
            });
  }

  private static Taxi taxi(String name) {
    return (Taxi)
        Proxy.newProxyInstance(
            Taxi.class.getClassLoader(),
            new Class<?>[] {Taxi.class},
            (proxy, method, args) -> switch (method.getName()) {
              case "getName" -> name;
              case "isEmpty", "hasCapacity", "isSpawned" -> true;
              case "isMoving" -> false;
              case "getCapacity", "getCurrentCapacity" -> 1;
              case "getPosition", "getTarget" -> new Position(0, 0);
              case "getTargets" -> TargetList.builder().build();
              case "getContainedPassengers", "getPlannedPassengers" -> Set.of();
              case "getTravelDistance", "getCurrentSpeed" -> 0d;
              case "getLastUpdate" -> 0L;
              case "getTargetOrderNode", "updatePosition" -> null;
              default -> throw new UnsupportedOperationException(method.getName());
            });
  }
}
