package de.sikeller.aqs.p2p.service;

import de.sikeller.aqs.p2p.api.NodeDescriptor;
import de.sikeller.aqs.p2p.api.NodeRuntimeStatus;
import de.sikeller.aqs.p2p.api.P2PMessage;
import de.sikeller.aqs.p2p.api.P2PNetwork;
import de.sikeller.aqs.p2p.api.P2PNodeService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class AbstractP2PNodeService implements P2PNodeService {
  public static final String TOPIC_TOPOLOGY_SCAN_REQUEST = "topology.scan.request";
  public static final String TOPIC_TOPOLOGY_SCAN_RESPONSE = "topology.scan.response";
  private static final String TOPIC_RIDE_REQUEST = "ride.request";

  private static final String OVERLAY_MAX_NEIGHBORS_PROPERTY = "aqs.p2p.overlay.maxNeighbors";
  private static final String OVERLAY_SHORTCUTS_PROPERTY = "aqs.p2p.overlay.shortcuts";
  private static final String OVERLAY_COLLECTOR_NODE_ID_PROPERTY = "aqs.p2p.overlay.collectorNodeId";
  private static final String OVERLAY_PIN_COLLECTOR_PROPERTY = "aqs.p2p.overlay.pinCollector";
  private static final String COLLECTOR_NODE_ID_PREFIX = "sim-collector-";

  private final NodeDescriptor descriptor;
  private final P2PNetwork network;
  private final List<P2PMessage> inbox = new CopyOnWriteArrayList<>();
  private final AtomicLong messagesSent = new AtomicLong();
  private final AtomicLong messagesReceived = new AtomicLong();
  private volatile ScheduledExecutorService statusScheduler;
  private volatile boolean running;

  protected AbstractP2PNodeService(NodeDescriptor descriptor, P2PNetwork network) {
    this.descriptor = descriptor;
    this.network = network;
  }

  @Override
  public NodeDescriptor descriptor() {
    return descriptor;
  }

  protected P2PNetwork network() {
    return network;
  }

  @Override
  public void start() {
    if (running) {
      return;
    }
    network.join(descriptor, this::handleIncoming);
    running = true;
    log.info("Node {} joined network as {}", descriptor.id(), descriptor.role());
    statusScheduler = createStatusScheduler();
    statusScheduler.scheduleAtFixedRate(this::logStatus, 0, 5, TimeUnit.SECONDS);
  }

  @Override
  public void stop() {
    if (!running) {
      return;
    }
    network.leave(descriptor.id());
    running = false;
    var scheduler = statusScheduler;
    statusScheduler = null;
    if (scheduler != null) {
      scheduler.shutdownNow();
    }
    log.info("Node {} left network", descriptor.id());
  }

  @Override
  public void publish(String topic, String payload) {
    publishMessage(topic, payload, null, null, node -> !node.id().equals(descriptor.id()));
  }

  protected void publish(String topic, String payload, Predicate<NodeDescriptor> targetFilter) {
    publishMessage(topic, payload, null, null, targetFilter);
  }

  protected void publishMessage(
      String topic, String payload, String requestId, String correlationId) {
    publishMessage(
        topic,
        payload,
        requestId,
        correlationId,
        node -> !node.id().equals(descriptor.id()));
  }

  protected void publishMessage(
      String topic,
      String payload,
      String requestId,
      String correlationId,
      Predicate<NodeDescriptor> targetFilter) {
    if (!running) {
      throw new IllegalStateException("Node service is not running.");
    }
    messagesSent.incrementAndGet();
    P2PMessage message =
        requestId == null
            ? P2PMessage.now(descriptor.id(), topic, payload)
            : P2PMessage.now(descriptor.id(), topic, payload, requestId, correlationId == null ? "" : correlationId);
    Set<String> overlayNeighborIds = overlayNeighborIds(topic);
    network.broadcast(message, node -> targetFilter.test(node) && overlayNeighborIds.contains(node.id()));
  }

  protected void sendTo(String targetNodeId, String topic, String payload) {
    sendToMessage(targetNodeId, topic, payload, null, null);
  }

  protected void sendToMessage(
      String targetNodeId,
      String topic,
      String payload,
      String requestId,
      String correlationId) {
    if (!running) {
      throw new IllegalStateException("Node service is not running.");
    }
    messagesSent.incrementAndGet();
    P2PMessage message =
        requestId == null
            ? P2PMessage.now(descriptor.id(), topic, payload)
            : P2PMessage.now(descriptor.id(), topic, payload, requestId, correlationId == null ? "" : correlationId);
    network.sendTo(targetNodeId, message);
  }

  public List<P2PMessage> inboxSnapshot() {
    return new ArrayList<>(inbox);
  }

  public Set<String> overlayNeighborIdsSnapshot() {
    Set<String> ids = new HashSet<>();
    overlayPeers(TOPIC_TOPOLOGY_SCAN_RESPONSE).forEach(peer -> ids.add(peer.id()));
    return ids;
  }

  protected void handleIncoming(P2PMessage message) {
    inbox.add(message);
    messagesReceived.incrementAndGet();

    if (TOPIC_TOPOLOGY_SCAN_REQUEST.equals(message.topic())) {
      respondToTopologyScan(message);
      return;
    }

    onMessage(message);
  }

  private void respondToTopologyScan(P2PMessage request) {
    Map<String, String> payload = new LinkedHashMap<>();
    payload.put("node", descriptor.id());
    payload.put("role", descriptor.role().name());

    String neighbors =
        overlayPeers(TOPIC_TOPOLOGY_SCAN_RESPONSE).stream()
            .map(NodeDescriptor::id)
            .sorted()
            .reduce((left, right) -> left + "," + right)
            .orElse("");
    payload.put("neighbors", neighbors);

    sendToMessage(
        request.senderId(),
        TOPIC_TOPOLOGY_SCAN_RESPONSE,
        KeyValuePayload.write(payload),
        request.requestId(),
        request.requestId());
  }

  private Set<String> overlayNeighborIds(String topic) {
    Set<String> ids = new HashSet<>();
    overlayPeers(topic).forEach(peer -> ids.add(peer.id()));
    return ids;
  }

  private List<NodeDescriptor> overlayPeers(String topic) {
    List<NodeDescriptor> peers =
        network.peers().stream().filter(peer -> !descriptor.id().equals(peer.id())).toList();
    if (peers.isEmpty()) {
      return List.of();
    }

    // Scan requests must reach all nodes so the collector can stitch a full topology snapshot.
    if (TOPIC_TOPOLOGY_SCAN_REQUEST.equals(topic)) {
      return peers;
    }

    // Collector node is the aggregation root and must have direct links to all peers.
    if (isCollectorNodeId(descriptor.id())) {
      return peers;
    }

    // Initial client requests are already range-filtered by business logic and should not be
    // additionally limited by overlay caps.
    if (descriptor.role() == de.sikeller.aqs.p2p.api.NodeRole.CLIENT
        && TOPIC_RIDE_REQUEST.equals(topic)) {
      return peers;
    }

    int maxNeighbors = Math.max(1, Integer.getInteger(OVERLAY_MAX_NEIGHBORS_PROPERTY, 3));
    int shortcuts = Math.max(0, Integer.getInteger(OVERLAY_SHORTCUTS_PROPERTY, 1));

    List<NodeDescriptor> routingPeers = peers;
    if (TOPIC_RIDE_REQUEST.equals(topic)
        && descriptor.role() == de.sikeller.aqs.p2p.api.NodeRole.VEHICLE) {
      // Forwarding between taxis should use taxi overlay neighbors only.
      routingPeers =
          peers.stream()
              .filter(peer -> peer.role() == de.sikeller.aqs.p2p.api.NodeRole.VEHICLE)
              .toList();
    }

    List<NodeDescriptor> selected = smallWorldPeers(routingPeers, maxNeighbors, shortcuts);
    if (!shouldPinCollectorPeers()) {
      return selected;
    }
    return includeCollectorPeers(selected, peers);
  }

  private boolean shouldPinCollectorPeers() {
    return Boolean.parseBoolean(System.getProperty(OVERLAY_PIN_COLLECTOR_PROPERTY, "true"));
  }

  private List<NodeDescriptor> includeCollectorPeers(
      List<NodeDescriptor> selected,
      List<NodeDescriptor> allPeers) {
    List<NodeDescriptor> collectorPeers =
        allPeers.stream().filter(peer -> isCollectorNodeId(peer.id())).toList();
    if (collectorPeers.isEmpty()) {
      return selected;
    }

    Map<String, NodeDescriptor> byId = new LinkedHashMap<>();
    selected.forEach(peer -> byId.put(peer.id(), peer));
    collectorPeers.forEach(peer -> byId.put(peer.id(), peer));
    return byId.values().stream().sorted(Comparator.comparing(NodeDescriptor::id)).toList();
  }

  private boolean isCollectorNodeId(String nodeId) {
    if (nodeId == null || nodeId.isBlank()) {
      return false;
    }
    String configured = System.getProperty(OVERLAY_COLLECTOR_NODE_ID_PROPERTY, "");
    if (!configured.isBlank() && configured.equals(nodeId)) {
      return true;
    }
    return nodeId.startsWith(COLLECTOR_NODE_ID_PREFIX);
  }

  private List<NodeDescriptor> smallWorldPeers(
      List<NodeDescriptor> peers,
      int maxNeighbors,
      int shortcuts) {
    List<NodeDescriptor> sortedPeers =
        peers.stream().sorted(Comparator.comparing(NodeDescriptor::id)).toList();
    if (sortedPeers.isEmpty()) {
      return List.of();
    }

    Map<String, NodeDescriptor> byId = new LinkedHashMap<>();
    sortedPeers.forEach(peer -> byId.put(peer.id(), peer));
    List<String> ringIds = new ArrayList<>(byId.keySet());
    ringIds.add(descriptor.id());
    ringIds = ringIds.stream().sorted().toList();

    int localSlots = Math.max(1, maxNeighbors - shortcuts);
    int selfIndex = ringIds.indexOf(descriptor.id());

    List<NodeDescriptor> selected = new ArrayList<>();
    for (int offset = 1; offset < ringIds.size() && selected.size() < localSlots; offset++) {
      String peerId = ringIds.get((selfIndex + offset) % ringIds.size());
      NodeDescriptor peer = byId.get(peerId);
      if (peer != null) {
        selected.add(peer);
      }
    }

    int remaining = Math.max(0, maxNeighbors - selected.size());
    if (remaining == 0) {
      return selected;
    }

    List<NodeDescriptor> shortcutsByStableHash =
        sortedPeers.stream()
            .filter(peer -> !selected.contains(peer))
            .sorted(
                Comparator.comparingLong(
                        (NodeDescriptor peer) -> stableShortcutScore(descriptor.id(), peer.id()))
                    .thenComparing(NodeDescriptor::id))
            .limit(remaining)
            .toList();
    selected.addAll(shortcutsByStableHash);
    return selected;
  }

  private int stableShortcutSeed(String id) {
    return Integer.rotateLeft(id.hashCode(), 7);
  }

  private long stableShortcutScore(String leftId, String rightId) {
    return Integer.toUnsignedLong(java.util.Objects.hash(leftId, rightId));
  }



  public NodeRuntimeStatus runtimeStatus() {
    return new NodeRuntimeStatus(
        descriptor.id(),
        descriptor.role(),
        running,
        network.peers().size(),
        inbox.size(),
        messagesSent.get(),
        messagesReceived.get());
  }

  protected void logStatus() {
    if (!running) {
      return;
    }
    log.info("[P2P-STATUS] {}", runtimeStatus().asLogLine());
  }

  private ScheduledExecutorService createStatusScheduler() {
    return Executors.newSingleThreadScheduledExecutor(
        r -> {
          Thread thread = new Thread(r, "p2p-status-" + descriptor.id());
          thread.setDaemon(true);
          return thread;
        });
  }

  protected abstract void onMessage(P2PMessage message);
}

