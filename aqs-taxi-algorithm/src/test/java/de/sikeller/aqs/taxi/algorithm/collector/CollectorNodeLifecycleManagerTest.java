package de.sikeller.aqs.taxi.algorithm.collector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.sikeller.aqs.p2p.api.NodeDescriptor;
import de.sikeller.aqs.p2p.api.P2PMessage;
import de.sikeller.aqs.p2p.api.P2PNetwork;
import de.sikeller.aqs.p2p.api.P2PSystemProperties;
import de.sikeller.aqs.p2p.service.ClientP2PService;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class CollectorNodeLifecycleManagerTest {

  @Test
  void ensureCollectorNodeStartsEmbeddedNodeAndStopClearsState() {
    AtomicReference<RecordingClientNode> created = new AtomicReference<>();
    CollectorNodeLifecycleManager manager =
        new CollectorNodeLifecycleManager(
            "sim-collector-local",
            "sim-collector-",
            RecordingNetwork::new,
            (group, discoveryPort, tcpPort, connectTimeoutMs, discoveryTimeoutMs) ->
                new RecordingNetwork(),
            (nodeId, network) -> {
              RecordingClientNode node = new RecordingClientNode(nodeId, network);
              created.set(node);
              return node;
            });

    manager.ensureCollectorNode(Map.of("p2pEmbeddedSimulation", 1), "239.255.42.99");

    assertNotNull(manager.network());
    assertNotNull(manager.clientNode());
    assertEquals("sim-collector-local", manager.collectorNodeId());
    assertEquals(
        "sim-collector-local",
        System.getProperty(P2PSystemProperties.OVERLAY_COLLECTOR_NODE_ID, ""));
    assertTrue(created.get().started);

    manager.stopCollectorNode();

    assertNull(manager.network());
    assertNull(manager.clientNode());
    assertEquals("", manager.collectorNodeId());
    assertEquals("", System.getProperty(P2PSystemProperties.OVERLAY_COLLECTOR_NODE_ID, ""));
    assertTrue(created.get().stopped);
  }

  @Test
  void ensureCollectorNodeStartsLanNodeWithConfiguredPorts() {
    AtomicReference<String> usedGroup = new AtomicReference<>("");
    AtomicReference<Integer> usedDiscoveryPort = new AtomicReference<>(-1);
    AtomicReference<Integer> usedTcpPort = new AtomicReference<>(-1);

    CollectorNodeLifecycleManager manager =
        new CollectorNodeLifecycleManager(
            "sim-collector-local",
            "sim-collector-",
            RecordingNetwork::new,
            (group, discoveryPort, tcpPort, connectTimeoutMs, discoveryTimeoutMs) -> {
              usedGroup.set(group);
              usedDiscoveryPort.set(discoveryPort);
              usedTcpPort.set(tcpPort);
              return new RecordingNetwork();
            },
            RecordingClientNode::new);

    manager.ensureCollectorNode(
        Map.of("p2pEmbeddedSimulation", 0, "p2pTcpPort", 46222, "p2pDiscoveryPort", 45999),
        "239.255.42.88");

    assertEquals("sim-collector-46222", manager.collectorNodeId());
    assertEquals("239.255.42.88", usedGroup.get());
    assertEquals(45999, usedDiscoveryPort.get());
    assertEquals(46222, usedTcpPort.get());

    manager.stopCollectorNode();
  }

  private static final class RecordingClientNode extends ClientP2PService {
    boolean started;
    boolean stopped;

    private RecordingClientNode(String nodeId, P2PNetwork network) {
      super(nodeId, network);
    }

    @Override
    public synchronized void start() {
      started = true;
    }

    @Override
    public synchronized void stop() {
      stopped = true;
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

