package de.sikeller.aqs.p2p.service;

import de.sikeller.aqs.p2p.api.*;
import de.sikeller.aqs.p2p.util.P2PGeoUtils;
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
  private static final String COLLECTOR_NODE_ID_PREFIX = "sim-collector-";
  private static final long DEFAULT_POSITION_TTL_TICKS = 200L;
  private static final int DEFAULT_MAX_INBOX_MESSAGES = 10_000;
  private static final String MAX_INBOX_MESSAGES_PROPERTY = "p2pMaxInboxMessages";
  private static final ConcurrentMap<String, VehiclePosition> vehiclePositions = new ConcurrentHashMap<>();
  private static final AtomicLong vehiclePositionRevision = new AtomicLong();
  // Track maximum observed position tick to avoid scanning the full map each time
  private static final AtomicLong vehiclePositionMaxTick = new AtomicLong();
  // Cache expensive overlay selection results keyed by topic. The cache is keyed by
  // a revision value composed from vehiclePositionRevision and a checksum of current peers.
  private final ConcurrentMap<String, CachedOverlay> overlaySelectionCache = new ConcurrentHashMap<>();

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
    maybeBumpVehiclePositionRevision(prev, position); // may increment vehiclePositionRevision O(1)
    // maintain max tick for quick access
    vehiclePositionMaxTick.updateAndGet(cur -> Math.max(cur, simulationTick));
    publishVehiclePosition(position); // publish may be O(n_peers)
  }

  protected Map<String, int[]> vehiclePositionSnapshot() {
    long nowTick = currentPositionTick();
    long ttlTicks = Math.max(1L, Long.getLong(P2PSystemProperties.OVERLAY_POSITION_TTL_TICKS, DEFAULT_POSITION_TTL_TICKS));
    Map<String, int[]> snapshot = new LinkedHashMap<>();
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

    // Compute a cheap revision: combine vehicle position revision and a checksum of current peers.
    long peersChecksum = computePeersChecksum(peers);
    long currentRevision = vehiclePositionRevision.get() ^ peersChecksum;
    CachedOverlay cached = overlaySelectionCache.get(topic);
    if (cached != null && cached.revision() == currentRevision) {
      return cached.selection();
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

    // Cache the result for subsequent quick lookups until peers or positions change.
    overlaySelectionCache.put(topic, new CachedOverlay(result, currentRevision, System.nanoTime()));

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
    maybeBumpVehiclePositionRevision(prev, next); // O(1) check + conditional increment
    // maintain max tick for quick access
    vehiclePositionMaxTick.updateAndGet(cur -> Math.max(cur, tick));
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
    // Use tracked max tick to avoid O(n) scans over the shared map. Falls back to 0 if none.
    return vehiclePositionMaxTick.get();
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
    // Snapshot positions to avoid repeated concurrent map lookups during sorting
    Map<String, VehiclePosition> positionsSnapshot = new HashMap<>(vehiclePositions);
    VehiclePosition self = positionsSnapshot.get(descriptor.id());
    if (self == null) {
      return List.of();
    }

    // stream with filters and sort: distance checks for each peer -> O(n_peers log n_peers)
    return sortedPeers.stream()
        .filter(peer -> peer.role() == NodeRole.VEHICLE)
        .filter(peer -> excludedPeerIds == null || !excludedPeerIds.contains(peer.id()))
        .filter(peer -> {
          VehiclePosition other = positionsSnapshot.get(peer.id());
          return other != null && distance(self, other) <= maxDistance;
        })
        .sorted(Comparator.comparingDouble((NodeDescriptor peer) -> distance(self, positionsSnapshot.get(peer.id())))
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

    // Select strategy: ring (evenly spaced) or kleinberg (probabilistic long-range links).
    String strategy = System.getProperty("p2p.overlay.shortcut.strategy", "kleinberg").trim().toLowerCase();

    // deterministic global seed used for stable selections
    long globalSeed = Long.getLong("worldSeed", 0L);
    // Global deterministic sampler: if a global fraction < 1.0 is requested, deterministically
    // select a subset of nodes (based on their id + globalSeed) that are allowed to create
    // shortcuts. This ensures a globally controlled number of nodes produce long-range links.
    double nodeProbability = 1.0;
    try {
      nodeProbability = Math.max(0.0, Math.min(1.0, Double.parseDouble(System.getProperty("p2p.overlay.shortcut.nodeProbability", "1.0"))));
    } catch (NumberFormatException ignored) {
    }
    if (nodeProbability < 1.0) {
      // build list of all node ids including self
      List<String> allIds = new ArrayList<>();
      allIds.add(descriptor.id());
      for (NodeDescriptor nd : sortedPeers) {
        if (nd != null && nd.id() != null) allIds.add(nd.id());
      }
      int total = allIds.size();
      int selectCount = (int) Math.round(nodeProbability * total);
      if (selectCount <= 0) {
        return List.of();
      }
      // rank ids by unsigned hash and pick top selectCount
      allIds.sort(Comparator.naturalOrder());
      allIds.sort((a, b) -> Long.compareUnsigned(Integer.toUnsignedLong(Objects.hash(b, globalSeed)), Integer.toUnsignedLong(Objects.hash(a, globalSeed))));
      Set<String> selected = new HashSet<>();
      for (int i = 0; i < Math.min(selectCount, allIds.size()); i++) {
        selected.add(allIds.get(i));
      }
      if (!selected.contains(descriptor.id())) {
        return List.of();
      }
    }

    if (!"kleinberg".equals(strategy)) {
      // ring selection: evenly spaced offsets on the ring
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

    // Kleinberg-like selection
    // exponent r controls preference for near vs far long-range links (Kleinberg uses r = grid-dimension)
    double r = 2.0;
    try {
      r = Math.max(0.0, Double.parseDouble(System.getProperty("p2p.overlay.shortcut.kleinberg.r", "2.0")));
    } catch (NumberFormatException ignored) {
    }
    // deterministic seed: reuse existing simulation/world seed if available to keep reproducible per-node shortcuts
    long seed = globalSeed ^ (long) Objects.hash(descriptor.id());
    Random rnd = new Random(seed);

    // (nodeProbability already applied globally above)

    List<NodeDescriptor> shortcuts = new ArrayList<>();

    // Build initial candidate list (indices)
    List<Integer> candidateIndices = new ArrayList<>();
    for (int idx = 0; idx < maxOffset; idx++) {
      NodeDescriptor candidate = sortedPeers.get(idx);
      if (candidate == null) continue;
      String cid = candidate.id();
      // Skip invalid ids, already-used ids and the local node itself
      if (cid == null || cid.isBlank() || cid.equals(descriptor.id()) || usedIds.contains(cid)) continue;
      candidateIndices.add(idx);
    }

    // snapshot positions for consistent distance calculations
    Map<String, VehiclePosition> positionsSnapshot = new HashMap<>(vehiclePositions);
    // Helper to compute distance between self and candidate: prefer geographic if available, else ring offset
    VehiclePosition selfPos = positionsSnapshot.get(descriptor.id());

    for (int slot = 0; slot < limit && !candidateIndices.isEmpty(); slot++) {
      // compute weights proportional to d^{-r}
      double totalWeight = 0.0;
      double[] weights = new double[candidateIndices.size()];
      for (int i = 0; i < candidateIndices.size(); i++) {
        int idx = candidateIndices.get(i);
        NodeDescriptor candidate = sortedPeers.get(idx);
        double dist;
        VehiclePosition other = positionsSnapshot.get(candidate.id());
        if (selfPos != null && other != null) {
          dist = P2PGeoUtils.distance(selfPos.x(), selfPos.y(), other.x(), other.y());
        } else {
          // fall back to ring offset distance (1..maxOffset)
          int ringOffset = Math.abs(idx - startIndex);
          ringOffset = Math.min(ringOffset, maxOffset - ringOffset);
          dist = Math.max(1.0, ringOffset);
        }
        // weight: inverse power of distance; avoid infinite weight by clamping
        double weight = Math.pow(Math.max(1e-6, dist), -r);
        weights[i] = weight;
        totalWeight += weight;
      }

      if (totalWeight <= 0.0) {
        break;
      }

      // roulette wheel selection
      double pick = rnd.nextDouble() * totalWeight;
      double acc = 0.0;
      int chosenIdx = -1;
      for (int i = 0; i < weights.length; i++) {
        acc += weights[i];
        if (pick <= acc) {
          chosenIdx = i;
          break;
        }
      }
      if (chosenIdx < 0) chosenIdx = weights.length - 1;

      int selectedPeerIdx = candidateIndices.get(chosenIdx);
      NodeDescriptor selected = sortedPeers.get(selectedPeerIdx);
      if (selected != null && usedIds.add(selected.id())) {
        shortcuts.add(selected);
      }

      // remove chosen index from candidateIndices
      candidateIndices.remove(chosenIdx);
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
    Map<String, VehiclePosition> positionsSnapshot = new HashMap<>(vehiclePositions);
    VehiclePosition self = positionsSnapshot.get(descriptor.id());
    if (self == null) {
      return List.of();
    }

    return sortedPeers.stream()
        .filter(peer -> peer.role() == NodeRole.VEHICLE)
        .filter(peer -> excludedPeerIds == null || !excludedPeerIds.contains(peer.id()))
        .sorted(
            Comparator
                .comparing((NodeDescriptor peer) -> positionsSnapshot.get(peer.id()) == null)
                .thenComparingDouble(peer -> distance(self, positionsSnapshot.get(peer.id())))
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

  // Throttled bump: only increment vehiclePositionRevision when movement or tick delta exceeds thresholds
  private void maybeBumpVehiclePositionRevision(VehiclePosition prev, VehiclePosition next) {
    if (prev == null || next == null) {
      vehiclePositionRevision.incrementAndGet(); // O(1)
      return;
    }
    long throttleTicks =
        Math.max(1L, Long.getLong(P2PSystemProperties.OVERLAY_POSITION_REVISION_THROTTLE_TICKS, 5L));
    int minMoveMeters = Math.max(0, Integer.getInteger(P2PSystemProperties.OVERLAY_POSITION_REVISION_MIN_MOVE_METERS, 50));
    long tickDelta = next.simulationTick() - prev.simulationTick();
    double dist = P2PGeoUtils.distance(prev.x(), prev.y(), next.x(), next.y());

    if (tickDelta >= throttleTicks) {
      vehiclePositionRevision.incrementAndGet();
      return;
    }
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

