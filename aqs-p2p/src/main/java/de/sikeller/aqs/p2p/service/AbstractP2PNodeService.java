package de.sikeller.aqs.p2p.service;

import de.sikeller.aqs.p2p.api.*;
import de.sikeller.aqs.p2p.util.P2PGeoUtils;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
  private static final String COLLECTOR_NODE_ID_PREFIX = "sim-collector-";
  private static final long DEFAULT_POSITION_TTL_TICKS = 200L;
  private static final int DEFAULT_MAX_INBOX_MESSAGES = 10_000;
  private static final String MAX_INBOX_MESSAGES_PROPERTY = "p2pMaxInboxMessages";
  private static final ConcurrentMap<String, VehiclePosition> vehiclePositions = new ConcurrentHashMap<>();
  private static final AtomicLong vehiclePositionRevision = new AtomicLong();

  private final NodeDescriptor descriptor;
  private final P2PNetwork network;
  private final ConcurrentLinkedQueue<P2PMessage> inbox = new ConcurrentLinkedQueue<>();
  private final AtomicInteger inboxSize = new AtomicInteger();
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
    if (descriptor.role() == NodeRole.VEHICLE) {
      vehiclePositions.remove(descriptor.id());
      vehiclePositionRevision.incrementAndGet();
    }
    inbox.clear();
    inboxSize.set(0);
    log.info("Node {} left network", descriptor.id());
  }

  protected void updateVehiclePositionSnapshot(int x, int y, long simulationTick) {
    if (descriptor.role() != NodeRole.VEHICLE) {
      return;
    }
    VehiclePosition position = new VehiclePosition(x, y, simulationTick);
    VehiclePosition prev = vehiclePositions.get(descriptor.id()); // O(1)
    vehiclePositions.put(descriptor.id(), position); // O(1)
    // Throttle revision bumps to avoid cache thrashing
    maybeBumpVehiclePositionRevision(descriptor.id(), prev, position); // may increment vehiclePositionRevision O(1)
    publishVehiclePosition(position); // publish may be O(n_peers)
  }

  protected Map<String, int[]> vehiclePositionSnapshot() {
    long nowTick = currentPositionTick();
    long ttlTicks = Math.max(1L, Long.getLong(P2PSystemProperties.OVERLAY_POSITION_TTL_TICKS, DEFAULT_POSITION_TTL_TICKS));
    Map<String, int[]> snapshot = new LinkedHashMap<>();
    // FIXME: perf
    // vehiclePositions.forEach: Worst-case O(n_vehicles) to scan and build snapshot
    vehiclePositions.forEach(
        (nodeId, position) -> {
          if (position == null || nowTick - position.simulationTick() > ttlTicks) {
            return;
          }
          snapshot.put(nodeId, new int[] {position.x(), position.y()});
        });
    return snapshot;
  }

  private void publishVehiclePosition(VehiclePosition position) {
    if (!running || position == null) {
      return;
    }
    Map<String, String> payload = new LinkedHashMap<>();
    payload.put(P2PPayloadKeys.POSITION_X, String.valueOf(position.x()));
    payload.put(P2PPayloadKeys.POSITION_Y, String.valueOf(position.y()));
    payload.put(P2PPayloadKeys.POSITION_TICK, String.valueOf(position.simulationTick()));
    publish(
        P2PTopics.VEHICLE_POSITION,
        KeyValuePayload.write(payload));
  }

  @Override
  public void publish(String topic, String payload) {
    publishMessage(topic, payload, null, null, node -> !node.id().equals(descriptor.id()));
  }

  protected synchronized void publishMessage(
      String topic,
      String payload,
      String requestId,
      String correlationId,
      Predicate<NodeDescriptor> targetFilter) {
    messagesSent.incrementAndGet();
    P2PMessage message =
        requestId == null
            ? P2PMessage.now(descriptor.id(), topic, payload)
            : P2PMessage.now(descriptor.id(), topic, payload, requestId, correlationId == null ? "" : correlationId);
    long t0 = System.nanoTime();
    Set<String> overlayNeighborIds = overlayNeighborIds(topic);
    long tOverlayNs = System.nanoTime() - t0;
    int targetCount = overlayNeighborIds.size();
    long tBroadcastStart = System.nanoTime();
    network.broadcast(message, node -> targetFilter.test(node) && overlayNeighborIds.contains(node.id()));
    long tBroadcastNs = System.nanoTime() - tBroadcastStart;
    if (log.isTraceEnabled()) {
      log.trace(
          "publishMessage topic={}, overlayCompute={}us, targets={}, broadcast={}us",
          topic,
          tOverlayNs / 1_000,
          targetCount,
          tBroadcastNs / 1_000);
    }
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
    // Copying inbox: O(n_inbox)
    return new ArrayList<>(inbox);
  }

  /**
   * Returns and clears the currently buffered inbox messages in arrival order.
   */
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
    // overlaySelection may perform expensive computation (overlay cache miss), worst-case O(n_peers log n_peers) or more
    overlaySelection(P2PTopics.TOPOLOGY_SCAN_RESPONSE).peers().forEach(peer -> ids.add(peer.id())); // Worst-case O(n_peers)
    return ids;
  }

  protected void handleIncoming(P2PMessage message) {
    inbox.add(message);
    inboxSize.incrementAndGet();
    trimInboxIfNeeded();
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

    // overlaySelection cost can be expensive on cache miss: worst-case O(n_peers log n_peers) or dominated by distanceBoundOverlayPeers
    OverlaySelection selection = overlaySelection(P2PTopics.TOPOLOGY_SCAN_RESPONSE); // Worst-case O(n_peers log n_peers)

    // Building neighbor string: sorting costs O(n_peers log n_peers)
    String neighbors =
        selection.peers().stream()
            .map(NodeDescriptor::id)
            .sorted()
            .reduce((left, right) -> left + "," + right)
            .orElse(""); // Worst-case O(n_peers log n_peers)
    payload.put(P2PPayloadKeys.NEIGHBORS, neighbors);

    String shortcutNeighbors =
        selection.shortcutPeerIds().stream().sorted().reduce((left, right) -> left + "," + right).orElse("");
    payload.put(P2PPayloadKeys.SHORTCUT_NEIGHBORS, shortcutNeighbors);

    sendToMessage(
        request.senderId(),
        P2PTopics.TOPOLOGY_SCAN_RESPONSE,
        KeyValuePayload.write(payload),
        request.requestId(),
        request.requestId());
  }

  private Set<String> overlayNeighborIds(String topic) {
    Set<String> ids = new HashSet<>();
    overlaySelection(topic).peers().forEach(peer -> ids.add(peer.id()));
    return ids;
  }

  private OverlaySelection overlaySelection(String topic) {
    List<NodeDescriptor> peers =
        network.peers().stream().filter(peer -> !descriptor.id().equals(peer.id())).toList();
    if (peers.isEmpty()) {
      return new OverlaySelection(List.of(), Set.of());
    }

    // Scan requests must reach all nodes so the collector can stitch a full topology snapshot.
    if (P2PTopics.TOPOLOGY_SCAN_REQUEST.equals(topic)) {
      return new OverlaySelection(peers, Set.of());
    }

    // Initial client requests are already seed-filtered by collector logic and must not be
    // additionally reduced by client-side overlay caps.
    if (descriptor.role() == NodeRole.CLIENT && P2PTopics.RIDE_REQUEST.equals(topic)) {
      return new OverlaySelection(peers, Set.of());
    }

    int minNeighbors = resolveOverlayMinNeighbors();
    int shortcuts = Math.max(0, Integer.getInteger(P2PSystemProperties.OVERLAY_SHORTCUTS, 1));
    double maxDistance = resolveOverlayMaxDistance();
    boolean pinCollector = shouldPinCollectorPeers();

    List<NodeDescriptor> routingPeers = resolveRoutingPeers(peers, topic);
    boolean vehicleRelevant =
        descriptor.role() == NodeRole.VEHICLE
            && (P2PTopics.RIDE_REQUEST.equals(topic)
                || P2PTopics.TOPOLOGY_SCAN_RESPONSE.equals(topic)
                || P2PTopics.VEHICLE_POSITION.equals(topic));

    int maxNeighbors = resolveOverlayMaxNeighbors();

    // distanceBoundOverlayPeers: can be O(n_peers log n_peers) due to sorting/filtering; may also call nearestVehiclePeers which sorts -> O(n log n)
    OverlaySelection smallWorld =
        distanceBoundOverlayPeers(routingPeers, minNeighbors, maxNeighbors, shortcuts, vehicleRelevant, maxDistance); // Worst-case O(n_peers log n_peers)
    List<NodeDescriptor> selected = smallWorld.peers();
    OverlaySelection result;
    if (!pinCollector) {
      result = new OverlaySelection(selected, smallWorld.shortcutPeerIds());
    } else {
      result =
          new OverlaySelection(
              includeCollectorPeers(selected, peers),
              smallWorld.shortcutPeerIds());
    }

    return result;
  }

  private int resolveOverlayMinNeighbors() {
    String configured = System.getProperty(P2PSystemProperties.OVERLAY_MIN_NEIGHBORS, "").trim();
    if (!configured.isBlank()) {
      try {
        return Math.max(1, Integer.parseInt(configured));
      } catch (NumberFormatException ignored) {
        // fall through to default
      }
    }
    return 1;
  }

  private int resolveOverlayMaxNeighbors() {
    String configured = System.getProperty(P2PSystemProperties.OVERLAY_MAX_NEIGHBORS, "").trim();
    if (!configured.isBlank()) {
      try {
        return Math.max(1, Integer.parseInt(configured));
      } catch (NumberFormatException ignored) {
        // fall through to default
      }
    }
    return Integer.MAX_VALUE;
  }

  private double resolveOverlayMaxDistance() {
    String configured = System.getProperty(P2PSystemProperties.OVERLAY_MAX_DISTANCE, "").trim();
    if (configured.isBlank()) {
      return Double.MAX_VALUE;
    }
    try {
      return Math.max(0d, Double.parseDouble(configured));
    } catch (NumberFormatException ignored) {
      return Double.MAX_VALUE;
    }
  }

  private List<NodeDescriptor> resolveRoutingPeers(List<NodeDescriptor> peers, String topic) {
    if (P2PTopics.RIDE_REQUEST.equals(topic)
        || P2PTopics.TOPOLOGY_SCAN_RESPONSE.equals(topic)
        || P2PTopics.VEHICLE_POSITION.equals(topic)) {
      return peers.stream().filter(peer -> peer.role() == NodeRole.VEHICLE).toList();
    }
    return peers;
  }

  private boolean shouldPinCollectorPeers() {
    return Boolean.parseBoolean(System.getProperty(P2PSystemProperties.OVERLAY_PIN_COLLECTOR, "false"));
  }

  private List<NodeDescriptor> includeCollectorPeers(List<NodeDescriptor> selected, List<NodeDescriptor> allPeers) {
    List<NodeDescriptor> collectorPeers =
        allPeers.stream().filter(peer -> isCollectorNodeId(peer.id())).toList();
    if (collectorPeers.isEmpty()) {
      return selected;
    }

    Map<String, NodeDescriptor> byId = new LinkedHashMap<>();
    selected.forEach(peer -> byId.put(peer.id(), peer));
    collectorPeers.forEach(peer -> byId.put(peer.id(), peer));
    // sorting values: O(k log k) where k = selected U collectorPeers
    return byId.values().stream().sorted(Comparator.comparing(NodeDescriptor::id)).toList(); // Worst-case O(k log k)
  }

  private boolean isCollectorNodeId(String nodeId) {
    if (nodeId == null || nodeId.isBlank()) {
      return false;
    }
    String configured = System.getProperty(P2PSystemProperties.OVERLAY_COLLECTOR_NODE_ID, "");
    if (!configured.isBlank() && configured.equals(nodeId)) {
      return true;
    }
    return nodeId.startsWith(COLLECTOR_NODE_ID_PREFIX);
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
    VehiclePosition prev = vehiclePositions.get(message.senderId()); // O(1)
    VehiclePosition next = new VehiclePosition(x, y, tick);
    vehiclePositions.put(message.senderId(), next); // O(1)
    maybeBumpVehiclePositionRevision(message.senderId(), prev, next); // O(1) check + conditional increment
  }

  private Integer parseInteger(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private Long parseLong(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Long.parseLong(value.trim());
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  // TODO: link with world ticks
  private long currentPositionTick() {
    long maxTick = 0L;
    // scan over vehiclePositions values: O(n_vehicles)
    for (VehiclePosition position : vehiclePositions.values()) {
      if (position != null) {
        maxTick = Math.max(maxTick, position.simulationTick());
      }
    }
    return maxTick;
  }

  private OverlaySelection distanceBoundOverlayPeers(
      List<NodeDescriptor> peers,
      int minNeighbors,
      int maxNeighbors,
      int shortcuts,
      boolean preferNearestVehicles,
      double maxDistance) {
    if (peers.isEmpty()) {
      return new OverlaySelection(List.of(), Set.of());
    }

    List<NodeDescriptor> sortedPeers = peers.stream().sorted(Comparator.comparing(NodeDescriptor::id)).toList();
    int startIndex = insertionIndex(sortedPeers, descriptor.id());

    List<NodeDescriptor> selected = new ArrayList<>();
    Set<String> selectedIds = new HashSet<>();

    if (preferNearestVehicles) {
      // vehiclePeersWithinDistance: filters + sorts -> worst-case O(n_peers log n_peers)
      List<NodeDescriptor> inRange = vehiclePeersWithinDistance(peers, maxDistance, selectedIds); // Worst-case O(n_peers log n_peers)
      selected.addAll(inRange);
      inRange.forEach(peer -> selectedIds.add(peer.id()));

      // Enforce cap: if maxNeighbors is smaller than minNeighbors, treat cap as minNeighbors
      int cap = Math.max(minNeighbors, maxNeighbors);
      if (selected.size() > cap) {
        selected = new ArrayList<>(selected.subList(0, cap));
        selectedIds.clear();
        selected.forEach(peer -> selectedIds.add(peer.id()));
      }

      if (selected.size() < minNeighbors) {
          // nearestVehiclePeers: may sort -> O(n_peers log n_peers)
          List<NodeDescriptor> nearestFallback = nearestVehiclePeers(peers, minNeighbors - selected.size(), selectedIds); // Worst-case O(n_peers log n_peers)
        selected.addAll(nearestFallback);
        nearestFallback.forEach(peer -> selectedIds.add(peer.id()));
      }
    } else {
      int cap = Math.max(minNeighbors, maxNeighbors);
      for (int offset = 0; offset < sortedPeers.size() && selected.size() < cap; offset++) {
        NodeDescriptor peer = sortedPeers.get((startIndex + offset) % sortedPeers.size());
        if (selectedIds.add(peer.id())) {
          selected.add(peer);
        }
      }
    }

    int shortcutSlots = Math.max(0, shortcuts);
    if (shortcutSlots == 0) {
      return new OverlaySelection(selected, Set.of());
    }

    // FIXME: perf
    // distributedShortcutPeers may be O(n_peers * limit) -> worst-case O(n_peers^2) when limit ~ n
    List<NodeDescriptor> shortcutsByStableHash =
        distributedShortcutPeers(sortedPeers, startIndex, selectedIds, shortcutSlots); // Worst-case O(n_peers^2)
    selected.addAll(shortcutsByStableHash);
    Set<String> shortcutPeerIds =
        shortcutsByStableHash.stream().map(NodeDescriptor::id).collect(java.util.stream.Collectors.toSet());
    return new OverlaySelection(selected, shortcutPeerIds);
  }

  private List<NodeDescriptor> vehiclePeersWithinDistance(
      List<NodeDescriptor> sortedPeers,
      double maxDistance,
      Set<String> excludedPeerIds) {
    if (descriptor.role() != NodeRole.VEHICLE || maxDistance < 0) {
      return List.of();
    }
    VehiclePosition self = vehiclePositions.get(descriptor.id());
    if (self == null) {
      return List.of();
    }

    // stream with filters and sort: distance checks for each peer -> O(n_peers log n_peers)
    return sortedPeers.stream()
        .filter(peer -> peer.role() == NodeRole.VEHICLE)
        .filter(peer -> excludedPeerIds == null || !excludedPeerIds.contains(peer.id()))
        .filter(
            peer -> {
              VehiclePosition other = vehiclePositions.get(peer.id());
              return other != null && distance(self, other) <= maxDistance;
            })
        .sorted(
            Comparator
                .comparingDouble((NodeDescriptor peer) -> distance(self, vehiclePositions.get(peer.id())))
                .thenComparing(NodeDescriptor::id))
        .toList();
  }

  private int insertionIndex(List<NodeDescriptor> sortedPeers, String selfId) {
    int low = 0;
    int high = sortedPeers.size();
    while (low < high) {
      int mid = (low + high) >>> 1;
      if (sortedPeers.get(mid).id().compareTo(selfId) < 0) {
        low = mid + 1;
      } else {
        high = mid;
      }
    }
    return low;
  }

  private List<NodeDescriptor> distributedShortcutPeers(
      List<NodeDescriptor> sortedPeers,
      int startIndex,
      Set<String> excludedPeerIds,
      int limit) {
    if (limit <= 0 || sortedPeers == null || sortedPeers.isEmpty()) {
      return List.of();
    }

    int maxOffset = sortedPeers.size();
    Set<String> usedIds = new HashSet<>();
    if (excludedPeerIds != null) {
      usedIds.addAll(excludedPeerIds);
    }

    List<NodeDescriptor> shortcuts = new ArrayList<>();
    for (int slot = 1; slot <= limit; slot++) {
      int suggestedOffset = Math.max(1, (int) Math.round((double) slot * maxOffset / (limit + 1.0)));
      for (int shift = 0; shift < maxOffset; shift++) {
        int offset = ((suggestedOffset - 1 + shift) % maxOffset) + 1;
        NodeDescriptor candidate = sortedPeers.get((startIndex + offset - 1) % maxOffset);
        if (!usedIds.add(candidate.id())) {
          continue;
        }
        shortcuts.add(candidate);
        break;
      }
      if (shortcuts.size() >= limit) {
        break;
      }
    }
    return shortcuts;
  }

  private List<NodeDescriptor> nearestVehiclePeers(
      List<NodeDescriptor> sortedPeers,
      int localSlots,
      Set<String> excludedPeerIds) {
    if (descriptor.role() != NodeRole.VEHICLE || localSlots <= 0) {
      return List.of();
    }
    VehiclePosition self = vehiclePositions.get(descriptor.id());
    if (self == null) {
      return List.of();
    }

    return sortedPeers.stream()
        .filter(peer -> peer.role() == NodeRole.VEHICLE)
        .filter(peer -> excludedPeerIds == null || !excludedPeerIds.contains(peer.id()))
        .sorted(
            Comparator
                .comparing((NodeDescriptor peer) -> vehiclePositions.get(peer.id()) == null)
                .thenComparingDouble(peer -> distance(self, vehiclePositions.get(peer.id())))
                .thenComparing(NodeDescriptor::id))
        .limit(localSlots)
        .toList();
  }

  private double distance(VehiclePosition left, VehiclePosition right) {
    if (left == null || right == null) {
      return Double.MAX_VALUE;
    }
    double dx = left.x() - right.x();
    double dy = left.y() - right.y();
    return Math.sqrt(dx * dx + dy * dy);
  }

  private record OverlaySelection(List<NodeDescriptor> peers, Set<String> shortcutPeerIds) {}

  private record VehiclePosition(int x, int y, long simulationTick) {}



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

  // Throttled bump: only increment vehiclePositionRevision when movement or tick delta exceeds thresholds
  private void maybeBumpVehiclePositionRevision(String nodeId, VehiclePosition prev, VehiclePosition next) {
    if (prev == null || next == null) {
      vehiclePositionRevision.incrementAndGet(); // O(1)
      return;
    }
    long throttleTicks =
        Math.max(1L, Long.getLong(P2PSystemProperties.OVERLAY_POSITION_REVISION_THROTTLE_TICKS, 5L));
    int minMoveMeters = Math.max(0, Integer.getInteger(P2PSystemProperties.OVERLAY_POSITION_REVISION_MIN_MOVE_METERS, 50));
    if (next.simulationTick() - prev.simulationTick() >= throttleTicks) {
      vehiclePositionRevision.incrementAndGet();
      return;
    }
    double dist = P2PGeoUtils.distance(prev.x(), prev.y(), next.x(), next.y());
    if (dist >= minMoveMeters) {
      vehiclePositionRevision.incrementAndGet();
    }
  }

  private void trimInboxIfNeeded() {
    int maxInboxMessages =
        Math.max(1, Integer.getInteger(MAX_INBOX_MESSAGES_PROPERTY, DEFAULT_MAX_INBOX_MESSAGES));
    while (inboxSize.get() > maxInboxMessages) {
      P2PMessage dropped = inbox.poll();
      if (dropped == null) {
        break;
      }
      inboxSize.decrementAndGet();
    }
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

