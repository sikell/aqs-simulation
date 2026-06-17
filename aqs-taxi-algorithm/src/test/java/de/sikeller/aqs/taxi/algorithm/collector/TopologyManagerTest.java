package de.sikeller.aqs.taxi.algorithm.collector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.sikeller.aqs.p2p.api.*;
import de.sikeller.aqs.p2p.service.ClientP2PService;
import de.sikeller.aqs.p2p.service.KeyValuePayload;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class TopologyManagerTest {

  @Test
  void handleTopologyScanResponseParsesAndUpsertsTopologyData() {
    Map<String, TopologyManager.TopologyData> views = new java.util.HashMap<>();
    AtomicInteger statusEvents = new AtomicInteger();

    TopologyManager manager =
        new TopologyManager(
            () -> null,
            () -> null,
            Map::of,
            () -> 0L,
            () -> Long.MIN_VALUE,
            views::put,
            (step, scanId) -> {},
            event -> statusEvents.incrementAndGet());

    String payload =
        KeyValuePayload.write(
            Map.of(
                P2PPayloadKeys.ROLE,
                "VEHICLE",
                P2PPayloadKeys.NEIGHBORS,
                "n1, n2, ,n2",
                P2PPayloadKeys.SHORTCUT_NEIGHBORS,
                "n2"));
    P2PMessage message =
        P2PMessage.now("vehicle-1", P2PTopics.TOPOLOGY_SCAN_RESPONSE, payload, "req-1", "corr-1");

    manager.handleTopologyScanResponse(message);

    TopologyManager.TopologyData data = views.get("vehicle-1");
    assertNotNull(data);
    assertEquals("VEHICLE", data.role());
    assertEquals(Set.of("n1", "n2"), data.neighborIds());
    assertEquals(Set.of("n2"), data.shortcutNeighborIds());
    assertEquals(1, statusEvents.get());
  }

  @Test
  void requestScanIfDueUsesLastScanAndForceFlag() {
    RecordingNetwork network = new RecordingNetwork();
    RecordingClientP2PService client = new RecordingClientP2PService("collector-1", network);
    AtomicLong step = new AtomicLong(10);
    AtomicLong lastScanAt = new AtomicLong(8);
    AtomicLong updatedStep = new AtomicLong(Long.MIN_VALUE);
    java.util.concurrent.atomic.AtomicReference<String> updatedScanId =
        new java.util.concurrent.atomic.AtomicReference<>("");
    Map<String, TopologyManager.TopologyData> views = new java.util.HashMap<>();

    TopologyManager manager =
        new TopologyManager(
            () -> client,
            () -> network,
            () -> Map.of("p2pTopologyScanTicks", 5),
            step::get,
            lastScanAt::get,
            views::put,
            (atStep, scanId) -> {
              lastScanAt.set(atStep);
              updatedStep.set(atStep);
              updatedScanId.set(scanId);
            },
            event -> {});

    manager.requestScanIfDue(false);
    assertEquals(0, client.requestCount.get());

    step.set(14);
    manager.requestScanIfDue(false);
    assertEquals(1, client.requestCount.get());
    assertEquals(14L, updatedStep.get());
    assertEquals("scan-1", updatedScanId.get());
    assertTrue(views.containsKey("collector-1"));

    step.set(15);
    manager.requestScanIfDue(false);
    assertEquals(1, client.requestCount.get());

    manager.requestScanIfDue(true);
    assertEquals(2, client.requestCount.get());
  }

  private static final class RecordingClientP2PService extends ClientP2PService {
    private final AtomicInteger requestCount = new AtomicInteger();

    private RecordingClientP2PService(String nodeId, P2PNetwork network) {
      super(nodeId, network);
    }

    @Override
    public String requestTopologyScan() {
      return "scan-" + requestCount.incrementAndGet();
    }

    @Override
    public Set<String> overlayNeighborIdsSnapshot() {
      return Set.of("vehicle-1", "vehicle-2");
    }
  }

  private static final class RecordingNetwork implements P2PNetwork {
    @Override
    public void join(NodeDescriptor node, Consumer<P2PMessage> messageHandler) {}

    @Override
    public void leave(String nodeId) {}

    @Override
    public void sendTo(String targetNodeId, P2PMessage message) {}

    @Override
    public void broadcast(P2PMessage message, Predicate<NodeDescriptor> targetFilter) {}

    @Override
    public Set<NodeDescriptor> peers() {
      return Set.of();
    }
  }
}
