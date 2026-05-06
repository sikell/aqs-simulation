package de.sikeller.aqs.p2p.service;

import de.sikeller.aqs.model.Position;
import de.sikeller.aqs.p2p.api.*;
import de.sikeller.aqs.p2p.service.config.P2PConfig;
import de.sikeller.aqs.p2p.service.config.SystemPropertyP2PConfig;
import de.sikeller.aqs.p2p.service.messaging.MessagePublisher;
import de.sikeller.aqs.p2p.service.messaging.MessagePublisherImpl;
import de.sikeller.aqs.p2p.service.overlay.OverlaySelector;
import de.sikeller.aqs.p2p.service.position.PositionManager;
import de.sikeller.aqs.p2p.service.position.PositionManagerImpl;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class AbstractP2PNodeService implements P2PNodeService {

  // Cache expensive overlay selection results keyed by topic. The cache is keyed by
  // a revision value composed from vehiclePositionRevision and a checksum of current peers.
  private final ConcurrentMap<String, CachedOverlay> overlaySelectionCache =
      new ConcurrentHashMap<>();

  private final NodeDescriptor descriptor;
  private final P2PNetwork network;
  private final ConcurrentLinkedQueue<P2PMessage> inbox = new ConcurrentLinkedQueue<>();
  private final AtomicInteger inboxSize = new AtomicInteger();
  private final AtomicLong messagesSent = new AtomicLong();
  private final AtomicLong messagesReceived = new AtomicLong();
  private volatile ScheduledExecutorService statusScheduler;
  private volatile boolean running;

  private PositionManager positionManager;

  private OverlaySelector overlaySelector;

  private MessagePublisher messagePublisher;

  private P2PConfig configAdapter;

  protected P2PConfig config() {
    return configAdapter;
  }

  protected AbstractP2PNodeService(NodeDescriptor descriptor, P2PNetwork network) {
    this.descriptor = descriptor;
    this.network = network;
    // wire default concrete implementations (can be overridden via alternate constructor)
    try {
      // create config first so dependent adapters can use it
      this.configAdapter = new SystemPropertyP2PConfig();
      this.positionManager = new PositionManagerImpl(this.configAdapter);
      this.overlaySelector =
          new de.sikeller.aqs.p2p.service.overlay.OverlaySelectorImpl(
              descriptor, this.positionManager, this.configAdapter);
      this.messagePublisher = new MessagePublisherImpl(descriptor, network, this.overlaySelector);
    } catch (Throwable t) {
      try {
        log.warn("Failed to initialise default P2P adapters", t);
      } catch (Throwable ignore) {
      }
    }
  }

  @Override
  public NodeDescriptor descriptor() {
    return descriptor;
  }

  protected P2PNetwork network() {
    return network;
  }

  @Override
  public synchronized void start() {
    if (running) {
      return;
    }
    network.join(descriptor, this::handleIncoming);
    running = true;
    log.info("Node {} joined network as {}", descriptor.id(), descriptor.role());
    statusScheduler = createStatusScheduler();
    statusScheduler.scheduleAtFixedRate(this::logStatus, 0, 10, TimeUnit.SECONDS);
  }

  @Override
  public synchronized void stop() {
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
    inbox.clear();
    inboxSize.set(0);
    log.info("Node {} left network", descriptor.id());
  }

  protected void updateVehiclePositionSnapshot(int x, int y, long simulationTick) {
    if (descriptor.role() != NodeRole.VEHICLE) {
      return;
    }
    // delegate storage and revision handling to PositionManager
    positionManager.updatePosition(descriptor.id(), x, y, simulationTick);
    // publish position as before
    publishVehiclePosition(x, y, simulationTick);
  }

  public Map<String, Position> vehiclePositionSnapshot() {
    // delegate to PositionManager (which applies TTL semantics)
    return positionManager.snapshot();
  }

  private void publishVehiclePosition(int x, int y, long simulationTick) {
    if (!running) return;
    Map<String, String> payload = new LinkedHashMap<>();
    payload.put(P2PPayloadKeys.POSITION_X, String.valueOf(x));
    payload.put(P2PPayloadKeys.POSITION_Y, String.valueOf(y));
    payload.put(P2PPayloadKeys.POSITION_TICK, String.valueOf(simulationTick));
    publish(P2PTopics.VEHICLE_POSITION, KeyValuePayload.write(payload));
  }

  @Override
  public void publish(String topic, String payload) {
    messagePublisher.publish(topic, payload);
  }

  protected synchronized void publishMessage(
      String topic,
      String payload,
      String requestId,
      String correlationId,
      Predicate<NodeDescriptor> targetFilter) {
    messagePublisher.publish(topic, payload, requestId, correlationId, targetFilter);
  }

  protected void sendToMessage(
      String targetNodeId, String topic, String payload, String requestId, String correlationId) {
    messagePublisher.sendTo(targetNodeId, topic, payload, requestId, correlationId);
  }

  public List<P2PMessage> inboxSnapshot() {
    // Copying inbox: O(n_inbox)
    return new ArrayList<>(inbox);
  }

  /** Returns and clears the currently buffered inbox messages in arrival order. */
  public List<P2PMessage> drainInbox() {
    List<P2PMessage> drained = new ArrayList<>();
    P2PMessage next;
    while ((next = inbox.poll()) != null) {
      drained.add(next);
      inboxSize.decrementAndGet();
    }
    // Draining inbox: O(n_messages) where n_messages is number drained
    return drained;
  }

  public Set<String> overlayNeighborIdsSnapshot() {
    Set<String> ids = new HashSet<>();
    overlaySelection(P2PTopics.TOPOLOGY_SCAN_RESPONSE).peers().forEach(peer -> ids.add(peer.id()));
    return ids;
  }

  /** Returns a live snapshot of this node's overlay neighbors and which of them are shortcuts. */
  public OverlayNeighborSnapshot overlayNeighborSnapshot() {
    OverlaySelection sel = overlaySelection(P2PTopics.TOPOLOGY_SCAN_RESPONSE);
    Set<String> neighborIds = new HashSet<>();
    sel.peers().forEach(peer -> neighborIds.add(peer.id()));
    return new OverlayNeighborSnapshot(neighborIds, Set.copyOf(sel.shortcutPeerIds()));
  }

  public record OverlayNeighborSnapshot(Set<String> neighborIds, Set<String> shortcutIds) {}

  protected void handleIncoming(P2PMessage message) {
    inbox.add(message);
    inboxSize.incrementAndGet();
    messagesReceived.incrementAndGet();

    if (P2PTopics.VEHICLE_POSITION.equals(message.topic())) {
      handleVehiclePosition(message);
      return;
    }

    if (P2PTopics.TOPOLOGY_SCAN_REQUEST.equals(message.topic())) {
      respondToTopologyScan(message);
      return;
    }

    onMessage(message);
  }

  private void respondToTopologyScan(P2PMessage request) {
    Map<String, String> payload = new LinkedHashMap<>();
    payload.put(P2PPayloadKeys.ROLE, descriptor.role().name());

    // overlaySelection cost can be expensive on cache miss: worst-case O(n_peers log n_peers) or
    // dominated by distanceBoundOverlayPeers
    OverlaySelection selection =
        overlaySelection(P2PTopics.TOPOLOGY_SCAN_RESPONSE); // Worst-case O(n_peers log n_peers)

    // Building neighbor string: sorting costs O(n_peers log n_peers)
    String neighbors =
        selection.peers().stream()
            .map(NodeDescriptor::id)
            .sorted()
            .reduce((left, right) -> left + "," + right)
            .orElse(""); // Worst-case O(n_peers log n_peers)
    payload.put(P2PPayloadKeys.NEIGHBORS, neighbors);

    String shortcutNeighbors =
        selection.shortcutPeerIds().stream()
            .sorted()
            .reduce((left, right) -> left + "," + right)
            .orElse("");
    payload.put(P2PPayloadKeys.SHORTCUT_NEIGHBORS, shortcutNeighbors);

    sendToMessage(
        request.senderId(),
        P2PTopics.TOPOLOGY_SCAN_RESPONSE,
        KeyValuePayload.write(payload),
        request.requestId(),
        request.requestId());
  }

  // Force overlay cache expiry every N ticks so position changes are picked up even when
  // positionRevision doesn't bump (e.g. slow-moving vehicles with high throttle/minMove settings).
  private static final long OVERLAY_CACHE_TICK_BUCKET = 3L;

  private OverlaySelection overlaySelection(String topic) {
    List<NodeDescriptor> peers =
        network.peers().stream().filter(peer -> !descriptor.id().equals(peer.id())).toList();
    if (peers.isEmpty()) {
      return new OverlaySelection(List.of(), Set.of());
    }

    long peersChecksum = computePeersChecksum(peers);
    long tickBucket = positionManager.currentMaxTick() / OVERLAY_CACHE_TICK_BUCKET;
    long currentRevision = positionManager.currentRevision() ^ peersChecksum ^ tickBucket;

    // Fast-path: return cached value when revision matches
    CachedOverlay cached = overlaySelectionCache.get(topic);
    if (cached != null && cached.revision() == currentRevision) {
      return cached.selection();
    }

    // Compute selection and update cache (use put to avoid re-entrant mapping functions)
    try {
      var sel = overlaySelector.select(topic, peers);
      OverlaySelection result = new OverlaySelection(sel.peers(), sel.shortcutPeerIds());
      CachedOverlay updated = new CachedOverlay(result, currentRevision, System.nanoTime());
      overlaySelectionCache.put(topic, updated);
      return updated.selection();
    } catch (Throwable ex) {
      // Do NOT cache on exception – returning all peers as fallback would mask overlay bugs
      // and produce a spurious fully-connected topology view. Log and return empty instead.
      log.warn(
          "[P2P-OVERLAY] overlay selection failed for topic={} self={}: {}",
          topic,
          descriptor.id(),
          ex.toString());
      return new OverlaySelection(List.of(), Set.of());
    }
  }

  private void handleVehiclePosition(P2PMessage message) {
    if (message == null || message.senderId() == null || message.senderId().isBlank()) {
      return;
    }
    Map<String, String> payload = KeyValuePayload.parse(message.payload());
    Integer x = parseInteger(payload.get(P2PPayloadKeys.POSITION_X));
    Integer y = parseInteger(payload.get(P2PPayloadKeys.POSITION_Y));
    Long tick = parseLong(payload.get(P2PPayloadKeys.POSITION_TICK));
    if (x == null || y == null || tick == null) {
      return;
    }
    // Delegate position updates to PositionManager which handles revision and TTL
    positionManager.updatePosition(message.senderId(), x, y, tick);
  }

  private Integer parseInteger(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return Integer.parseInt(value.trim());
  }

  private Long parseLong(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return Long.parseLong(value.trim());
  }

  private record OverlaySelection(List<NodeDescriptor> peers, Set<String> shortcutPeerIds) {}

  private record CachedOverlay(OverlaySelection selection, long revision, long createdAtNs) {}

  private long computePeersChecksum(List<NodeDescriptor> peers) {
    long acc = 1469598103934665603L; // FNV offset basis-like
    for (NodeDescriptor peer : peers) {
      if (peer == null || peer.id() == null) continue;
      long h = peer.id().hashCode();
      acc ^= (h + 0x9e3779b97f4a7c15L + (acc << 6) + (acc >> 2));
      acc *= 1099511628211L;
    }
    return acc;
  }

  public NodeRuntimeStatus runtimeStatus() {
    return new NodeRuntimeStatus(
        descriptor.id(),
        descriptor.role(),
        running,
        network.peers().size(),
        inboxSize.get(),
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
