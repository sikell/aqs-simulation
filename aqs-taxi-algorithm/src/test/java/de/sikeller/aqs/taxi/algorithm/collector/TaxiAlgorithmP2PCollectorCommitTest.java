package de.sikeller.aqs.taxi.algorithm.collector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.sikeller.aqs.p2p.api.P2PMessage;
import de.sikeller.aqs.p2p.api.P2PPayloadKeys;
import de.sikeller.aqs.p2p.api.P2PSystemProperties;
import de.sikeller.aqs.p2p.api.P2PTopics;
import de.sikeller.aqs.p2p.service.KeyValuePayload;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
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

  private static void restore(String key, String value) {
    if (value == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, value);
    }
  }
}
