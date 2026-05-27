package de.sikeller.aqs.p2p.service;

import de.sikeller.aqs.model.Position;
import de.sikeller.aqs.model.SpawnScenario;
import de.sikeller.aqs.p2p.api.NodeDescriptor;
import de.sikeller.aqs.p2p.api.NodeRole;
import de.sikeller.aqs.p2p.api.P2PMessage;
import de.sikeller.aqs.p2p.api.P2PNetwork;
import de.sikeller.aqs.p2p.api.P2PPayloadKeys;
import de.sikeller.aqs.p2p.api.P2PSystemProperties;
import de.sikeller.aqs.p2p.api.P2PTopics;
import de.sikeller.aqs.p2p.service.strategy.NearestVehicleRequestSelectionStrategy;
import de.sikeller.aqs.p2p.service.strategy.VehicleRequestCandidate;
import de.sikeller.aqs.p2p.service.strategy.VehicleRequestSelectionStrategies;
import de.sikeller.aqs.p2p.util.IdleRoamingController;
import de.sikeller.aqs.p2p.util.P2PGeoUtils;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class VehicleP2PService extends AbstractP2PNodeService {
  private static final String TRIGGER_AVAILABILITY = "availability";
  private static final String TRIGGER_MOVEMENT = "movement";
  private static final String TRIGGER_INCOMING = "incoming";
  private static final long DEFAULT_VEHICLE_COMMIT_LEASE_TICKS = 20L;
  private static final double DEFAULT_ASSUMED_SPEED_MPS = 12.0;
  private static final long DEFAULT_VEHICLE_REOFFER_MIN_INTERVAL_TICKS = 3L;
  private static final int DEFAULT_VEHICLE_REOFFER_MOVE_DISTANCE_M = 200;
  private static final long DEFAULT_VEHICLE_REQUEST_CACHE_TTL_TICKS = 600L;
  private static final long DEFAULT_CLEANUP_INTERVAL_TICKS = 10L;
  private static final int MAX_FORWARDED_PAYLOAD_CACHE_SIZE = 200;
  private final ConcurrentMap<String, String> committedByRequest = new ConcurrentHashMap<>();
  // Track first-seen tick per requestId so we can evict stale entries along with openRideRequests
  private final ConcurrentMap<String, Long> seenRideRequests = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, OpenRideRequest> openRideRequests = new ConcurrentHashMap<>();
  // cache for previously computed forwarded payload strings to avoid repeated parsing/serialization
  private final ConcurrentMap<String, String> forwardedPayloadCache = new ConcurrentHashMap<>();
  private volatile long busyUntilTick;
  private volatile long currentSimulationTick;
  private volatile boolean externallyAvailable = true;
  private volatile Integer simulationX;
  private volatile Integer simulationY;
  private volatile long lastCleanupTick = 0L;
  private volatile long lastActivityTick = -1L; // -1 = not yet initialized

  /**
   * -- GETTER -- Get the idle roaming controller for direct access (e.g., for HQ pickup
   * registration).
   */
  @Getter private final IdleRoamingController idleRoamingController = new IdleRoamingController();

  private volatile int mapMaxX = 0; // 0 = not yet initialized from world
  private volatile int mapMaxY = 0;

  /** Called by the collector to provide the authoritative World map bounds for idle travel. */
  public synchronized void setMapBounds(int maxX, int maxY) {
    this.mapMaxX = Math.max(1, maxX);
    this.mapMaxY = Math.max(1, maxY);
  }

  /** Global simulation scenario propagated by collector (same scenario used by world generator). */
  public synchronized void setSpawnScenario(SpawnScenario scenario) {
    idleRoamingController.setSpawnScenario(scenario);
  }

  public VehicleP2PService(String nodeId, P2PNetwork network) {
    super(new NodeDescriptor(nodeId, NodeRole.VEHICLE), network);
  }

  /** Sends a RIDE_COMMIT directly to the collector/origin node (autonomous vehicle decision). */
  private void sendDirectCommit(String targetNodeId, String requestId) {
    Map<String, String> payload = new LinkedHashMap<>();
    payload.put(P2PPayloadKeys.REQUEST_ID, requestId);
    payload.put(P2PPayloadKeys.VEHICLE, descriptor().id());
    String nodeId = descriptor().id();
    payload.put(
        P2PPayloadKeys.TAXI_NAME,
        nodeId.startsWith("vehicle-") ? nodeId.substring("vehicle-".length()) : nodeId);
    sendToMessage(
        targetNodeId, P2PTopics.RIDE_COMMIT, KeyValuePayload.write(payload), requestId, requestId);
  }

  public synchronized void setSimulationState(boolean available, int positionX, int positionY) {
    setSimulationState(available, positionX, positionY, currentSimulationTick + 1);
  }

  public synchronized void setSimulationState(
      boolean available, int positionX, int positionY, long simulationTick) {
    currentSimulationTick = Math.max(currentSimulationTick, simulationTick);
    boolean becameAvailable = !externallyAvailable && available;
    boolean movedEnough = movedEnoughForRetrigger(positionX, positionY);
    externallyAvailable = available;
    simulationX = positionX;
    simulationY = positionY;
    updateVehiclePositionSnapshot(positionX, positionY, currentSimulationTick);
    idleRoamingController.clearIfReached(simulationX, simulationY);
    // updateVehiclePositionSnapshot -> vehiclePositions.put + publishVehiclePosition (O(1) map
    // update + O(n_peers) publish overlay selection)

    // Initialize activity tracker once on first availability
    if (available && lastActivityTick < 0L) {
      lastActivityTick = currentSimulationTick;
    }

    // Reset activity tracker only on actual state changes (became available or moved enough)
    if (lastActivityTick >= 0L && (becameAvailable || movedEnough)) {
      lastActivityTick = currentSimulationTick;
    }

    // If taxi became unavailable or is now busy, cancel any persistent idle target
    if (!available || isVehicleBusy()) {
      idleRoamingController.clearAll();
    }

    if (becameAvailable) {
      retriggerOpenRequests(TRIGGER_AVAILABILITY);
      return;
    }
    if (available && movedEnough) {
      // movement resets idle intent
      idleRoamingController.clearCurrentTarget();
      retriggerOpenRequests(TRIGGER_MOVEMENT);
    }
  }

  @Override
  protected void onMessage(P2PMessage message) {
    log.debug("Vehicle node {} received: {} -> <payload/>", descriptor().id(), message.topic());

    if (P2PTopics.RIDE_REQUEST.equals(message.topic())) {
      handleRideRequest(message);
    }
    if (P2PTopics.RIDE_ASSIGNED.equals(message.topic())) {
      handleRideAssigned(message);
    }
  }

  /**
   * Handles a RIDE_ASSIGNED broadcast from the client/collector. Drops the request from the local
   * open queue immediately. If this vehicle is the loser (not the winner), the busy-lease is also
   * cleared so the taxi is available for the next request without waiting for TTL.
   */
  private void handleRideAssigned(P2PMessage message) {
    String requestId = message.requestId();
    if (requestId == null || requestId.isBlank()) {
      return;
    }
    Map<String, String> payload = KeyValuePayload.parse(message.payload());
    String winnerVehicleId = payload.get(P2PPayloadKeys.WINNER_VEHICLE);

    // Mark as committed so we never re-offer this request
    committedByRequest.putIfAbsent(
        requestId, winnerVehicleId != null ? winnerVehicleId : "assigned");
    openRideRequests.remove(requestId);

    boolean iWon = descriptor().id().equals(winnerVehicleId);
    if (!iWon) {
      // Loser: release the busy-lease immediately so this taxi can serve the next request
      busyUntilTick = 0L;
      log.info(
          "Vehicle {} released busy-lease (lost requestId={} winner={})",
          descriptor().id(),
          requestId,
          winnerVehicleId);
    } else {
      // Winner: clear any pending idle target – taxi is now serving a real ride
      idleRoamingController.clearAll();
      log.debug(
          "Vehicle {} received own win announcement requestId={}", descriptor().id(), requestId);
    }
  }

  private void handleRideRequest(P2PMessage message) {
    String requestId = message.requestId();
    if (requestId == null || requestId.isBlank()) {
      return;
    }
    log.debug(
        "Vehicle {} handling ride request requestId={} sender={} payload={}",
        descriptor().id(),
        requestId,
        message.senderId(),
        message.payload());
    if (seenRideRequests.putIfAbsent(requestId, currentSimulationTick) != null) {
      log.debug("Ignoring duplicate ride request {} from {}", requestId, message.senderId());
      return;
    }

    cleanupStaleOpenRequestsIfNeeded();
    Map<String, String> requestPayload = KeyValuePayload.parse(message.payload());
    String originNodeId =
        requestPayload.getOrDefault(P2PPayloadKeys.ORIGIN_NODE, message.senderId());

    openRideRequests.compute(
        requestId,
        (id, existing) -> {
          if (existing == null) {
            return new OpenRideRequest(id, originNodeId, requestPayload, currentSimulationTick);
          }
          // avoid unnecessary copies: reuse parsed payload map reference
          existing.originNodeId = originNodeId;
          existing.payload = requestPayload;
          return existing;
        });
    log.debug(
        "Vehicle {} queued openRideRequest requestId={} origin={} payload={}",
        descriptor().id(),
        requestId,
        originNodeId,
        requestPayload);
    commitForRequestIfPossible(requestId);

    // Forward first-seen requests regardless of local offer to improve decentralized visibility.
    forwardRideRequest(message, requestPayload);
  }

  /**
   * Autonomous commit decision: the vehicle evaluates its fitness for the request (range check,
   * best-known-vehicle check) and – if it qualifies – commits directly by sending RIDE_COMMIT to
   * the collector. No RIDE_OFFER/RIDE_ACCEPT round-trip required. First commit at the collector
   * wins (first-come-first-serve collision resolution).
   */
  private void commitForRequestIfBest(OpenRideRequest openRequest, String trigger) {
    if (openRequest == null || committedByRequest.containsKey(openRequest.requestId)) {
      return;
    }
    log.debug(
        "Vehicle {} evaluating commit requestId={} trigger={} busy={} payload={}",
        descriptor().id(),
        openRequest.requestId,
        trigger,
        isVehicleBusy(),
        openRequest.payload);
    if (isVehicleBusy() || !shouldOffer(openRequest.payload)) {
      log.debug(
          "Vehicle {} skipping requestId={} (busy={} inRange={})",
          descriptor().id(),
          openRequest.requestId,
          isVehicleBusy(),
          shouldOffer(openRequest.payload));
      return;
    }

    int etaSeconds = estimateEtaSeconds(openRequest.payload);
    if (!shouldReoffer(openRequest, currentSimulationTick)) {
      return;
    }
    if (!isBestKnownVehicleForRequest(openRequest, etaSeconds)) {
      log.debug(
          "Vehicle {} is not best known vehicle for requestId={} selfEta={} payload={}",
          descriptor().id(),
          openRequest.requestId,
          etaSeconds,
          openRequest.payload);
      return;
    }

    // Autonomously commit: mark busy with a lease (protects against race in embedded mode)
    String existingWinner =
        committedByRequest.putIfAbsent(openRequest.requestId, descriptor().id());
    if (existingWinner != null) {
      return; // another local thread already committed
    }
    long leaseTicks =
        Math.max(
            1L,
            Long.getLong(
                P2PSystemProperties.VEHICLE_COMMIT_LEASE_TICKS,
                DEFAULT_VEHICLE_COMMIT_LEASE_TICKS));
    busyUntilTick = currentSimulationTick + leaseTicks;
    openRideRequests.remove(openRequest.requestId);
    openRequest.lastOfferAtTick = currentSimulationTick;
    // Discard any pending idle travel target – the taxi is now committed to a real ride.
    // Also clear persistent idle intent so no further idle targets are generated while busy.
    // The World-side idle waypoint is automatically cleared when planClientForTaxi adds
    // a real Order, which triggers TargetList.planOrders() and clears flattenedTargets.
    idleRoamingController.clearAll();
    sendDirectCommit(openRequest.originNodeId, openRequest.requestId);
    log.info(
        "Vehicle {} autonomously committed requestId={} to origin={} trigger={} etaSeconds={}",
        descriptor().id(),
        openRequest.requestId,
        openRequest.originNodeId,
        trigger,
        etaSeconds);
  }

  private boolean isBestKnownVehicleForRequest(OpenRideRequest openRequest, int selfEtaSeconds) {
    if (openRequest == null || openRequest.payload == null) {
      return true;
    }
    if (isForwardedRequest(openRequest.payload)) {
      // Forwarded requests should stay serviceable by neighbors to keep k-hop diffusion useful.
      return true;
    }

    Integer reqX = parseCoordinate(openRequest.payload.get(P2PPayloadKeys.REQUEST_X)); // O(1)
    Integer reqY = parseCoordinate(openRequest.payload.get(P2PPayloadKeys.REQUEST_Y)); // O(1)
    if (reqX == null || reqY == null) {
      return true;
    }

    Set<String> overlayNeighbors =
        overlayNeighborIdsSnapshot(); // Worst-case O(n_peers) (overlaySelection + list copy)
    Map<String, Position> knownVehiclePositions =
        vehiclePositionSnapshot(); // Worst-case O(n_vehicles) (iterate vehiclePositions map)
    String bestNodeId = descriptor().id();
    int bestEtaSeconds = selfEtaSeconds;

    // Compare only against selected overlay neighbors (instead of all peers).
    for (String neighborId : overlayNeighbors) {
      if (neighborId == null || neighborId.isBlank() || neighborId.equals(descriptor().id())) {
        continue;
      }

      Position peerPosition = knownVehiclePositions.get(neighborId); // O(1) map lookup
      if (peerPosition == null) {
        continue;
      }

      int peerEtaSeconds =
          estimateEtaSecondsFromPosition(
              peerPosition.getX(), peerPosition.getY(), reqX, reqY); // O(1) math
      if (peerEtaSeconds < bestEtaSeconds
          || (peerEtaSeconds == bestEtaSeconds && neighborId.compareTo(bestNodeId) < 0)) {
        bestEtaSeconds = peerEtaSeconds;
        bestNodeId = neighborId;
      }
    }
    return descriptor().id().equals(bestNodeId);
  }

  private boolean isForwardedRequest(Map<String, String> requestPayload) {
    if (requestPayload == null) {
      return false;
    }
    String forwardedBy = requestPayload.get(P2PPayloadKeys.FORWARDED_BY);
    return forwardedBy != null && !forwardedBy.isBlank();
  }

  private void retriggerOpenRequests(String trigger) {
    if (openRideRequests.isEmpty()) {
      return;
    }
    cleanupStaleOpenRequests(); // Worst-case O(n_openRequests)
    commitForSelectedOpenRequest(trigger); // Worst-case O(m log m + n_neighbors)
  }

  private void commitForSelectedOpenRequest(String trigger) {
    if (openRideRequests.isEmpty()) {
      return;
    }
    if (isVehicleBusy()) {
      return;
    }

    OpenRideRequest selected =
        selectOpenRequest(); // Worst-case O(m log m) (strategy.select may sort); m =
    // eligibleRequests
    if (selected == null) {
      return;
    }
    commitForRequestIfBest(selected, trigger); // Worst-case O(n_neighbors)
  }

  private void commitForRequestIfPossible(String requestId) {
    OpenRideRequest request = openRideRequests.get(requestId); // O(1)
    if (request == null) {
      return;
    }
    if (isVehicleBusy()) {
      return;
    }
    commitForRequestIfBest(request, TRIGGER_INCOMING); // Worst-case O(n_neighbors)
  }

  private OpenRideRequest selectOpenRequest() {
    long nowTick = currentSimulationTick;
    Set<OpenRideRequest> eligibleRequests =
        openRideRequests.values().stream() // Worst-case O(n_openRequests) scan
            .filter(request -> !committedByRequest.containsKey(request.requestId))
            .filter(
                request -> {
                  if (!shouldOffer(request.payload)) {
                    return false;
                  }
                  return shouldReoffer(request, nowTick); // O(1)
                })
            .collect(Collectors.toCollection(LinkedHashSet::new));
    if (eligibleRequests.isEmpty()) {
      return null;
    }

    String strategyKey =
        System.getProperty(
            P2PSystemProperties.VEHICLE_OPEN_REQUEST_STRATEGY,
            NearestVehicleRequestSelectionStrategy.KEY); // O(1)
    var strategy = VehicleRequestSelectionStrategies.resolve(strategyKey); // O(1) map lookup

    Optional<VehicleRequestCandidate> selectedCandidate =
        strategy.select(
            eligibleRequests.stream()
                .map(this::toCandidate)
                .toList()); // Worst-case O(m log m) (sorting) or O(m) if linear
    if (selectedCandidate.isEmpty()) {
      return null;
    }

    String selectedRequestId = selectedCandidate.get().requestId();
    return eligibleRequests.stream()
        .filter(request -> request.requestId.equals(selectedRequestId))
        .findFirst()
        .orElse(null);
  }

  private VehicleRequestCandidate toCandidate(OpenRideRequest request) {
    return new VehicleRequestCandidate(
        request.requestId,
        request.firstSeenAtTick,
        requestDistanceToVehicle(request),
        request.payload == null ? Map.of() : request.payload);
  }

  private double requestDistanceToVehicle(OpenRideRequest request) {
    Integer reqX = parseCoordinate(request.payload.get(P2PPayloadKeys.REQUEST_X));
    Integer reqY = parseCoordinate(request.payload.get(P2PPayloadKeys.REQUEST_Y));
    if (reqX == null || reqY == null || simulationX == null || simulationY == null) {
      return Double.MAX_VALUE;
    }
    return P2PGeoUtils.distance(simulationX, simulationY, reqX, reqY);
  }

  private boolean shouldReoffer(OpenRideRequest openRequest, long nowTick) {
    if (openRequest.lastOfferAtTick == 0L) {
      return true;
    }
    long minIntervalTicks = Math.max(0L, resolveReofferMinIntervalTicks());
    return nowTick - openRequest.lastOfferAtTick >= minIntervalTicks;
  }

  private boolean movedEnoughForRetrigger(int positionX, int positionY) {
    if (simulationX == null || simulationY == null) {
      return true;
    }
    int minMoveDistance = Math.max(1, resolveReofferMoveDistanceMeters()); // O(1)
    return P2PGeoUtils.distance(simulationX, simulationY, positionX, positionY)
        >= minMoveDistance; // O(1)
  }

  private void cleanupStaleOpenRequests() {
    long ttlTicks =
        Math.max(
            1L,
            Long.getLong(
                P2PSystemProperties.VEHICLE_REQUEST_CACHE_TTL_TICKS,
                DEFAULT_VEHICLE_REQUEST_CACHE_TTL_TICKS));
    long nowTick = currentSimulationTick;
    openRideRequests
        .entrySet()
        .removeIf(entry -> nowTick - entry.getValue().firstSeenAtTick > ttlTicks);
    // Also evict stale seenRideRequests entries to prevent unbounded growth over long runs
    seenRideRequests.entrySet().removeIf(entry -> nowTick - entry.getValue() > ttlTicks);
    // Evict forwarded payload cache if too large
    if (forwardedPayloadCache.size() > MAX_FORWARDED_PAYLOAD_CACHE_SIZE) {
      forwardedPayloadCache.clear();
    }
  }

  private void cleanupStaleOpenRequestsIfNeeded() {
    long now = currentSimulationTick;
    if (now - lastCleanupTick >= DEFAULT_CLEANUP_INTERVAL_TICKS) {
      lastCleanupTick = now;
      cleanupStaleOpenRequests();
    }
  }

  private boolean isVehicleBusy() {
    return !externallyAvailable || currentSimulationTick < busyUntilTick;
  }

  private boolean shouldOffer(Map<String, String> requestPayload) {
    if (requestPayload == null) {
      return true;
    }
    if (Boolean.parseBoolean(
        System.getProperty(P2PSystemProperties.VEHICLE_ALLOW_OUTSIDE_CLIENT_RANGE, "false"))) {
      return true;
    }

    // k-hop expansion: forwarded requests are intentionally serviceable by overlay neighbors.
    String forwardedBy = requestPayload.get(P2PPayloadKeys.FORWARDED_BY);
    if (forwardedBy != null && !forwardedBy.isBlank()) {
      return true;
    }

    Integer reqX = parseCoordinate(requestPayload.get(P2PPayloadKeys.REQUEST_X)); // O(1)
    Integer reqY = parseCoordinate(requestPayload.get(P2PPayloadKeys.REQUEST_Y)); // O(1)
    Integer searchRadius =
        parseNonNegativeIntOrNull(requestPayload.get(P2PPayloadKeys.SEARCH_RADIUS)); // O(1)
    if (reqX == null
        || reqY == null
        || searchRadius == null
        || simulationX == null
        || simulationY == null) {
      return true;
    }

    boolean inRange =
        P2PGeoUtils.distance(simulationX, simulationY, reqX, reqY) <= searchRadius; // O(1)
    if (!inRange) {
      log.debug(
          "Vehicle {} skips offer for out-of-range request (requestId? unknown req=({}, {}) radius={} vehicle=({}, {}))",
          descriptor().id(),
          reqX,
          reqY,
          searchRadius,
          simulationX,
          simulationY);
    }
    return inRange;
  }

  private int estimateEtaSeconds(Map<String, String> requestPayload) {
    Integer reqX = parseCoordinate(requestPayload.get(P2PPayloadKeys.REQUEST_X)); // O(1)
    Integer reqY = parseCoordinate(requestPayload.get(P2PPayloadKeys.REQUEST_Y)); // O(1)
    if (reqX == null || reqY == null || simulationX == null || simulationY == null) {
      return 120;
    }

    return estimateEtaSecondsFromPosition(simulationX, simulationY, reqX, reqY); // O(1)
  }

  private int estimateEtaSecondsFromPosition(int startX, int startY, int reqX, int reqY) {
    double speedMps =
        Math.max(
            0.1, parseDouble(System.getProperty(P2PSystemProperties.VEHICLE_ASSUMED_SPEED_MPS)));
    return P2PGeoUtils.etaSeconds(startX, startY, reqX, reqY, speedMps);
  }

  private void forwardRideRequest(P2PMessage message, Map<String, String> requestPayload) {
    int incomingHops = parseNonNegativeInt(requestPayload.get(P2PPayloadKeys.HOPS_REMAINING));
    if (incomingHops <= 0) {
      log.info(
          "Vehicle {} not forwarding requestId={} (remainingHops={} reason=ttl-exhausted)",
          descriptor().id(),
          message.requestId(),
          incomingHops);
      return;
    }

    int nextHops = incomingHops - 1;
    Set<String> overlayNeighbors = overlayNeighborIdsSnapshot();

    // ensure the forwarded payload preserves the original origin information
    String originNodeId =
        requestPayload.getOrDefault(P2PPayloadKeys.ORIGIN_NODE, message.senderId());
    String cacheKey = message.requestId() + ":" + nextHops + ":" + originNodeId;
    String forwardedPayloadStr = forwardedPayloadCache.get(cacheKey);
    if (forwardedPayloadStr == null) {
      Map<String, String> m = new LinkedHashMap<>(requestPayload);
      m.put(P2PPayloadKeys.ORIGIN_NODE, originNodeId);
      m.put(P2PPayloadKeys.HOPS_REMAINING, String.valueOf(nextHops));
      m.put(P2PPayloadKeys.FORWARDED_BY, descriptor().id());
      String computed = KeyValuePayload.write(m);
      String prev = forwardedPayloadCache.putIfAbsent(cacheKey, computed);
      forwardedPayloadStr = prev == null ? computed : prev;
    }

    final Set<String> forwardTargets = overlayNeighbors;
    publishMessage(
        P2PTopics.RIDE_REQUEST,
        forwardedPayloadStr,
        message.requestId(),
        message.correlationId(),
        node ->
            node.role() == NodeRole.VEHICLE
                && !node.id().equals(descriptor().id())
                && !node.id().equals(message.senderId())
                && forwardTargets.contains(node.id()));
    log.debug(
        "Vehicle {} forwarded requestId={} nextHops={}",
        descriptor().id(),
        message.requestId(),
        nextHops);
  }

  private double parseDouble(String value) {
    if (value == null || value.isBlank()) {
      return DEFAULT_ASSUMED_SPEED_MPS;
    }
    return Double.parseDouble(value.trim());
  }

  private int parseNonNegativeInt(String value) {
    if (value == null || value.isBlank()) {
      return 0;
    }
    return Math.max(0, Integer.parseInt(value.trim()));
  }

  private Integer parseNonNegativeIntOrNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return Math.max(0, Integer.parseInt(value.trim()));
  }

  private long resolveReofferMinIntervalTicks() {
    return Long.getLong(
        P2PSystemProperties.VEHICLE_REOFFER_MIN_INTERVAL_TICKS,
        DEFAULT_VEHICLE_REOFFER_MIN_INTERVAL_TICKS);
  }

  private int resolveReofferMoveDistanceMeters() {
    return Integer.getInteger(
        P2PSystemProperties.VEHICLE_REOFFER_MOVE_DISTANCE_METERS,
        DEFAULT_VEHICLE_REOFFER_MOVE_DISTANCE_M);
  }

  private Integer parseCoordinate(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return Integer.parseInt(value.trim());
  }

  public Set<String> knownClientIdsSnapshot() {
    cleanupStaleOpenRequests(); // O(n_openRequests)
    Set<String> knownClientIds = new HashSet<>();
    for (OpenRideRequest request : openRideRequests.values()) { // O(n_openRequests)
      if (request == null || request.payload == null) {
        continue;
      }
      String clientName = request.payload.get(P2PPayloadKeys.CLIENT_NAME);
      if (clientName != null && !clientName.isBlank()) {
        knownClientIds.add(clientName);
      }
    }
    return knownClientIds;
  }

  /**
   * Public method to trigger idle travel check independently of position updates. Called by the
   * collector during each simulation step to check if vehicles should travel randomly. This allows
   * idle travel to happen autonomously even when the vehicle position hasn't changed.
   */
  public synchronized void checkIdleTravelAtStep(long simulationTick) {
    currentSimulationTick = Math.max(currentSimulationTick, simulationTick);
    idleRoamingController.checkAndTrigger(
        currentSimulationTick,
        isVehicleBusy(),
        externallyAvailable,
        lastActivityTick,
        simulationX,
        simulationY,
        mapMaxX,
        mapMaxY,
        descriptor().id(),
        target ->
            updateVehiclePositionSnapshot(target.getX(), target.getY(), currentSimulationTick));
  }

  /**
   * Advances only the internal simulation tick without re-evaluating movement/availability logic.
   * Useful for collector-side state sync when taxi state is unchanged.
   */
  public synchronized void advanceSimulationTick(long simulationTick) {
    currentSimulationTick = Math.max(currentSimulationTick, simulationTick);
    cleanupStaleOpenRequestsIfNeeded();
  }

  /**
   * Atomically poll and clear any pending idle travel target generated by this vehicle. Collector
   * (embedded) should call this to obtain one-time idle waypoint to apply to World.
   */
  public Optional<Position> pollIdleTravelTarget() {
    return idleRoamingController.pollIdleTravelTarget();
  }

  private static class OpenRideRequest {
    private final String requestId;
    private volatile String originNodeId;
    private volatile Map<String, String> payload;
    private final long firstSeenAtTick;
    private volatile long lastOfferAtTick;

    private OpenRideRequest(
        String requestId, String originNodeId, Map<String, String> payload, long firstSeenAtTick) {
      this.requestId = requestId;
      this.originNodeId = originNodeId;
      this.payload = payload;
      this.firstSeenAtTick = firstSeenAtTick;
    }
  }
}
