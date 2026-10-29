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
import de.sikeller.aqs.p2p.service.util.TriConsumer;
import de.sikeller.aqs.p2p.util.IdleRoamingController;
import de.sikeller.aqs.p2p.util.P2PGeoUtils;
import de.sikeller.aqs.p2p.util.P2PRunContext;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class VehicleP2PService extends AbstractP2PNodeService {
  private static final String TRIGGER_AVAILABILITY = "availability";
  private static final String TRIGGER_MOVEMENT = "movement";
  private static final String TRIGGER_INCOMING = "incoming";
  private static final long DEFAULT_VEHICLE_COMMIT_LEASE_TICKS = 20L;
  private static final double DEFAULT_ASSUMED_SPEED_MPS = 12.0;
  private static final Set<String> REMOTE_CONFIG_KEYS =
      Set.of(
          P2PSystemProperties.OVERLAY_MIN_NEIGHBORS,
          P2PSystemProperties.OVERLAY_MAX_NEIGHBORS,
          P2PSystemProperties.OVERLAY_MAX_DISTANCE,
          P2PSystemProperties.OVERLAY_SHORTCUTS,
          P2PSystemProperties.OVERLAY_COLLECTOR_NODE_ID,
          P2PSystemProperties.OVERLAY_PIN_COLLECTOR,
          P2PSystemProperties.OVERLAY_POSITION_TTL_TICKS,
          P2PSystemProperties.OVERLAY_POSITION_REVISION_THROTTLE_TICKS,
          P2PSystemProperties.OVERLAY_POSITION_REVISION_MIN_MOVE_METERS,
          P2PSystemProperties.OVERLAY_SHORTCUT_STRATEGY,
          P2PSystemProperties.OVERLAY_SHORTCUT_KLEINBERG_R,
          P2PSystemProperties.OVERLAY_SHORTCUT_NODE_PROBABILITY,
          P2PSystemProperties.VEHICLE_OPEN_REQUEST_STRATEGY,
          P2PSystemProperties.VEHICLE_COMMIT_LEASE_TICKS,
          P2PSystemProperties.VEHICLE_ASSUMED_SPEED_MPS,
          P2PSystemProperties.VEHICLE_REOFFER_MIN_INTERVAL_TICKS,
          P2PSystemProperties.VEHICLE_REOFFER_MOVE_DISTANCE_METERS,
          P2PSystemProperties.VEHICLE_REQUEST_CACHE_TTL_TICKS,
          P2PSystemProperties.VEHICLE_ALLOW_OUTSIDE_CLIENT_RANGE,
          P2PSystemProperties.VEHICLE_ROAMING_ENABLED,
          P2PSystemProperties.VEHICLE_IDLE_THRESHOLD_TICKS,
          P2PSystemProperties.VEHICLE_IDLE_CHECK_THROTTLE_TICKS,
          P2PSystemProperties.VEHICLE_RANDOM_TRAVEL_MAX_DISTANCE_METERS,
          P2PSystemProperties.VEHICLE_IDLE_ROAMING_STRATEGY,
          P2PSystemProperties.VEHICLE_IDLE_SEEN_CLIENT_TTL_TICKS);
  private static final long DEFAULT_VEHICLE_REOFFER_MIN_INTERVAL_TICKS = 3L;
  private static final int DEFAULT_VEHICLE_REOFFER_MOVE_DISTANCE_M = 200;
  private static final long DEFAULT_VEHICLE_REQUEST_CACHE_TTL_TICKS = 600L;
  private static final long DEFAULT_VEHICLE_IDLE_SEEN_CLIENT_TTL_TICKS = 1000L;
  private static final long DEFAULT_CLEANUP_INTERVAL_TICKS = 10L;
  private static final int MAX_FORWARDED_PAYLOAD_CACHE_SIZE = 200;

  private volatile long cachedCommitLeaseTicks = DEFAULT_VEHICLE_COMMIT_LEASE_TICKS;
  private volatile double cachedAssumedSpeedMps = DEFAULT_ASSUMED_SPEED_MPS;
  private volatile long cachedReofferMinIntervalTicks = DEFAULT_VEHICLE_REOFFER_MIN_INTERVAL_TICKS;
  private volatile int cachedReofferMoveDistanceM = DEFAULT_VEHICLE_REOFFER_MOVE_DISTANCE_M;
  private volatile long cachedRequestCacheTtlTicks = DEFAULT_VEHICLE_REQUEST_CACHE_TTL_TICKS;
  private volatile long cachedSeenClientTtlTicks = DEFAULT_VEHICLE_IDLE_SEEN_CLIENT_TTL_TICKS;

  /**
   * When true, skip isBestKnownVehicle — collector selects winner from multiple commits. -- SETTER
   * -- Enable embedded mode: skips isBestKnownVehicle (collector picks winner).
   */
  @Setter private volatile boolean embeddedMode = false;

  /** Direct commit callback for embedded mode: (requestId, vehicleNodeId, taxiName). */
  @Setter private volatile TriConsumer<String, String, String> directCommitCallback;

  /**
   * Resolves peer vehicle node IDs to local instances for direct forwarding. -- SETTER -- Set a
   * resolver to find peer vehicles for direct forwarding (embedded mode).
   */
  @Setter private volatile Function<String, VehicleP2PService> peerResolver;

  // Track first-seen tick per requestId for deduplication and eviction
  private final ConcurrentMap<String, Long> seenRideRequests = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, OpenRideRequest> openRideRequests = new ConcurrentHashMap<>();
  // Prevent one still-valid client observation from being weighted repeatedly.
  private final ConcurrentMap<String, Long> roamingRegisteredRideRequests =
      new ConcurrentHashMap<>();
  private final ConcurrentMap<String, String> forwardedPayloadCache = new ConcurrentHashMap<>();
  private volatile long busyUntilTick;
  private volatile long currentSimulationTick;
  private volatile boolean externallyAvailable = true;
  private volatile Integer simulationX;
  private volatile Integer simulationY;
  private volatile long lastCleanupTick = 0L;
  private volatile long lastActivityTick = -1L;

  @Getter private final IdleRoamingController idleRoamingController = new IdleRoamingController();

  private volatile int mapMaxX = 0;
  private volatile int mapMaxY = 0;

  public synchronized void setMapBounds(int maxX, int maxY) {
    this.mapMaxX = Math.max(1, maxX);
    this.mapMaxY = Math.max(1, maxY);
  }

  /** Global simulation scenario propagated by collector. */
  public synchronized void setSpawnScenario(SpawnScenario scenario) {
    idleRoamingController.setSpawnScenario(scenario);
  }

  /** Refresh cached config from system properties. Call once at init. */
  public void refreshCachedConfig() {
    cachedCommitLeaseTicks =
        Math.max(
            1L,
            P2PRunContext.getLong(
                P2PSystemProperties.VEHICLE_COMMIT_LEASE_TICKS,
                DEFAULT_VEHICLE_COMMIT_LEASE_TICKS));
    cachedReofferMinIntervalTicks =
        Math.max(
            0L,
            P2PRunContext.getLong(
                P2PSystemProperties.VEHICLE_REOFFER_MIN_INTERVAL_TICKS,
                DEFAULT_VEHICLE_REOFFER_MIN_INTERVAL_TICKS));
    cachedReofferMoveDistanceM =
        Math.max(
            1,
            P2PRunContext.getInt(
                P2PSystemProperties.VEHICLE_REOFFER_MOVE_DISTANCE_METERS,
                DEFAULT_VEHICLE_REOFFER_MOVE_DISTANCE_M));
    cachedRequestCacheTtlTicks =
        Math.max(
            1L,
            P2PRunContext.getLong(
                P2PSystemProperties.VEHICLE_REQUEST_CACHE_TTL_TICKS,
                DEFAULT_VEHICLE_REQUEST_CACHE_TTL_TICKS));
    cachedSeenClientTtlTicks =
        Math.max(
            1L,
            P2PRunContext.getLong(
                P2PSystemProperties.VEHICLE_IDLE_SEEN_CLIENT_TTL_TICKS,
                DEFAULT_VEHICLE_IDLE_SEEN_CLIENT_TTL_TICKS));
    String speedStr = P2PRunContext.getProperty(P2PSystemProperties.VEHICLE_ASSUMED_SPEED_MPS);
    cachedAssumedSpeedMps =
        (speedStr == null || speedStr.isBlank())
            ? DEFAULT_ASSUMED_SPEED_MPS
            : Math.max(0.1, Double.parseDouble(speedStr.trim()));
  }

  public VehicleP2PService(String nodeId, P2PNetwork network) {
    super(new NodeDescriptor(nodeId, NodeRole.VEHICLE), network);
    refreshCachedConfig();
  }

  /** Sends a RIDE_COMMIT to the origin node. */
  private void sendDirectCommit(String targetNodeId, String requestId) {
    String nodeId = descriptor().id();
    String taxiName =
        nodeId.startsWith("vehicle-") ? nodeId.substring("vehicle-".length()) : nodeId;

    var callback = directCommitCallback;
    if (embeddedMode && callback != null) {
      P2PRunContext.measureCommunication(() -> callback.accept(requestId, nodeId, taxiName));
      return;
    }

    Map<String, String> payload = new LinkedHashMap<>();
    payload.put(P2PPayloadKeys.REQUEST_ID, requestId);
    payload.put(P2PPayloadKeys.VEHICLE, nodeId);
    payload.put(P2PPayloadKeys.TAXI_NAME, taxiName);
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
    boolean movedEnough =
        simulationX == null
            || simulationY == null
            || P2PGeoUtils.distance(simulationX, simulationY, positionX, positionY)
                >= cachedReofferMoveDistanceM;
    externallyAvailable = available;
    simulationX = positionX;
    simulationY = positionY;
    updateVehiclePositionSnapshot(positionX, positionY, currentSimulationTick);
    idleRoamingController.clearIfReached(simulationX, simulationY);

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
    if (P2PTopics.VEHICLE_STATE.equals(message.topic())) {
      handleVehicleState(message);
    }
  }

  /** Direct notification that a ride was assigned (embedded mode). */
  public void notifyRideAssigned(String requestId, String winnerVehicleId) {
    if (requestId == null || requestId.isBlank()) {
      return;
    }
    seenRideRequests.putIfAbsent(requestId, currentSimulationTick);
    openRideRequests.remove(requestId);

    boolean iWon = descriptor().id().equals(winnerVehicleId);
    if (!iWon) {
      busyUntilTick = 0L;
    } else {
      idleRoamingController.clearAll();
    }
  }

  /**
   * Direct ride request delivery for embedded mode. Bypasses P2P message construction and triggers
   * k-hop forwarding via direct calls to overlay neighbors.
   */
  public void deliverRideRequestDirect(
      String requestId, String originNodeId, Map<String, String> payload) {
    if (requestId == null || requestId.isBlank()) {
      return;
    }
    if (seenRideRequests.putIfAbsent(requestId, currentSimulationTick) != null) {
      return;
    }
    cleanupStaleOpenRequestsIfNeeded();
    openRideRequests.compute(
        requestId,
        (id, existing) -> {
          if (existing == null) {
            return new OpenRideRequest(id, originNodeId, payload, currentSimulationTick);
          }
          existing.originNodeId = originNodeId;
          existing.payload = payload;
          return existing;
        });
    boolean committedLocally = commitForRequestIfPossible(requestId);
    if (committedLocally) {
      return;
    }

    int hopsRemaining = parseNonNegativeInt(payload.get(P2PPayloadKeys.HOPS_REMAINING));
    if (hopsRemaining > 0 && peerResolver != null) {
      int nextHops = hopsRemaining - 1;
      Set<String> overlayNeighbors = overlayNeighborIdsSnapshot();
      Map<String, String> forwardedPayload = new LinkedHashMap<>(payload);
      forwardedPayload.put(P2PPayloadKeys.ORIGIN_NODE, originNodeId);
      forwardedPayload.put(P2PPayloadKeys.HOPS_REMAINING, String.valueOf(nextHops));
      forwardedPayload.put(P2PPayloadKeys.FORWARDED_BY, descriptor().id());
      for (String neighborId : overlayNeighbors) {
        if (neighborId.equals(descriptor().id())) {
          continue;
        }
        VehicleP2PService neighbor = peerResolver.apply(neighborId);
        if (neighbor != null) {
          neighbor.deliverRideRequestDirect(requestId, originNodeId, forwardedPayload);
        }
      }
    }
  }

  /** Handles RIDE_ASSIGNED: drops request from queue and frees busy-lease for losers. */
  private void handleRideAssigned(P2PMessage message) {
    String requestId = message.requestId();
    if (requestId == null || requestId.isBlank()) {
      return;
    }
    Map<String, String> payload = KeyValuePayload.parse(message.payload());
    String winnerVehicleId = payload.get(P2PPayloadKeys.WINNER_VEHICLE);

    seenRideRequests.putIfAbsent(requestId, currentSimulationTick);
    openRideRequests.remove(requestId);

    boolean iWon = descriptor().id().equals(winnerVehicleId);
    if (!iWon) {
      busyUntilTick = 0L;
      log.debug(
          "Vehicle {} released busy-lease (lost requestId={} winner={})",
          descriptor().id(),
          requestId,
          winnerVehicleId);
    } else {
      Integer pickupX = parseNullableInt(payload.get(P2PPayloadKeys.PICKUP_X));
      Integer pickupY = parseNullableInt(payload.get(P2PPayloadKeys.PICKUP_Y));
      if (pickupX != null && pickupY != null) {
        idleRoamingController.registerPickupPosition(pickupX, pickupY);
      }
      idleRoamingController.clearAll();
      publishRoamingSnapshot();
      log.debug(
          "Vehicle {} received own win announcement requestId={}", descriptor().id(), requestId);
    }
  }

  private void publishRoamingSnapshot() {
    Map<String, String> payload = new LinkedHashMap<>();
    putPoint(
        payload,
        P2PPayloadKeys.HQ_X,
        P2PPayloadKeys.HQ_Y,
        idleRoamingController.getHqPositionSnapshot());
    putPoint(
        payload,
        P2PPayloadKeys.REVISIT_X,
        P2PPayloadKeys.REVISIT_Y,
        idleRoamingController.getRevisitTargetSnapshot());
    putPoint(
        payload,
        P2PPayloadKeys.AVG_TOTAL_X,
        P2PPayloadKeys.AVG_TOTAL_Y,
        idleRoamingController.getAvgTotalPositionSnapshot());
    if (!payload.isEmpty()) {
      publish(P2PTopics.VEHICLE_ROAMING, KeyValuePayload.write(payload));
    }
  }

  private void putPoint(Map<String, String> payload, String xKey, String yKey, int[] point) {
    if (point == null || point.length < 2) {
      return;
    }
    payload.put(xKey, String.valueOf(point[0]));
    payload.put(yKey, String.valueOf(point[1]));
  }

  private Integer parseNullableInt(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return Integer.parseInt(value.trim());
  }

  private void handleVehicleState(P2PMessage message) {
    Map<String, String> payload = KeyValuePayload.parse(message.payload());
    applyRemoteConfig(payload);
    String rawX = payload.get(P2PPayloadKeys.POSITION_X);
    String rawY = payload.get(P2PPayloadKeys.POSITION_Y);
    String rawTick = payload.get(P2PPayloadKeys.POSITION_TICK);
    if (rawX == null || rawY == null || rawTick == null) {
      return;
    }
    String rawMapMaxX = payload.get(P2PPayloadKeys.MAP_MAX_X);
    String rawMapMaxY = payload.get(P2PPayloadKeys.MAP_MAX_Y);
    if (rawMapMaxX != null && rawMapMaxY != null) {
      setMapBounds(Integer.parseInt(rawMapMaxX), Integer.parseInt(rawMapMaxY));
    }
    setSpawnScenario(SpawnScenario.fromLabel(payload.get(P2PPayloadKeys.SPAWN_SCENARIO)));
    long tick = Long.parseLong(rawTick);
    setSimulationState(
        Boolean.parseBoolean(payload.getOrDefault(P2PPayloadKeys.AVAILABLE, "true")),
        Integer.parseInt(rawX),
        Integer.parseInt(rawY),
        tick);
    checkIdleTravelAtStep(tick);
  }

  private void applyRemoteConfig(Map<String, String> payload) {
    boolean changed = false;
    for (String key : REMOTE_CONFIG_KEYS) {
      String value = payload.get(key);
      if (value != null && !value.equals(P2PRunContext.getProperty(key))) {
        P2PRunContext.setProperty(key, value);
        changed = true;
      }
    }
    if (changed) {
      refreshCachedConfig();
      clearOverlaySelectionCache();
    }
  }

  private void handleRideRequest(P2PMessage message) {
    String requestId = message.requestId();
    if (requestId == null || requestId.isBlank()) {
      return;
    }
    if (seenRideRequests.putIfAbsent(requestId, currentSimulationTick) != null) {
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
          existing.originNodeId = originNodeId;
          existing.payload = requestPayload;
          return existing;
        });
    boolean committedLocally = commitForRequestIfPossible(requestId);

    // Forward first-seen requests to improve decentralized visibility.
    if (!committedLocally) {
      forwardRideRequest(message, requestPayload);
    }
  }

  /**
   * Autonomous commit decision: evaluates fitness and commits. In embedded mode, isBestKnownVehicle
   * is skipped (collector resolves conflicts).
   */
  private boolean commitForRequestIfBest(OpenRideRequest openRequest, String trigger) {
    if (openRequest == null) {
      return false;
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
      registerRequestForRoaming(openRequest);
      return false;
    }

    if (!shouldReoffer(openRequest, currentSimulationTick)) {
      registerRequestForRoaming(openRequest);
      return false;
    }

    // In embedded mode, skip expensive isBestKnownVehicle — just commit if in range.
    if (!embeddedMode && hasOwnPosition() && hasRequestPosition(openRequest.payload)) {
      int reqX = Integer.parseInt(openRequest.payload.get(P2PPayloadKeys.REQUEST_X));
      int reqY = Integer.parseInt(openRequest.payload.get(P2PPayloadKeys.REQUEST_Y));
      int etaSeconds =
          P2PGeoUtils.etaSeconds(simulationX, simulationY, reqX, reqY, cachedAssumedSpeedMps);
      if (!isBestKnownVehicleForRequest(openRequest, etaSeconds)) {
        registerRequestForRoaming(openRequest);
        return false;
      }
    }

    // Atomically claim so concurrent triggers cannot double-commit
    if (!openRideRequests.remove(openRequest.requestId, openRequest)) {
      registerRequestForRoaming(openRequest);
      return false;
    }
    busyUntilTick = currentSimulationTick + cachedCommitLeaseTicks;
    openRequest.lastOfferAtTick = currentSimulationTick;
    idleRoamingController.clearAll();
    sendDirectCommit(openRequest.originNodeId, openRequest.requestId);
    log.debug(
        "Vehicle {} autonomously committed requestId={} to origin={} trigger={}",
        descriptor().id(),
        openRequest.requestId,
        openRequest.originNodeId,
        trigger);
    return true;
  }

  private void registerRequestForRoaming(OpenRideRequest openRequest) {
    String reqId = openRequest.requestId;
    String rawX = openRequest.payload.get(P2PPayloadKeys.REQUEST_X);
    String rawY = openRequest.payload.get(P2PPayloadKeys.REQUEST_Y);
    if (rawX != null
        && rawY != null
        && roamingRegisteredRideRequests.putIfAbsent(reqId, currentSimulationTick) == null) {
      try {
        idleRoamingController.registerSeenClientPosition(
            Integer.parseInt(rawX), Integer.parseInt(rawY), currentSimulationTick);
        publishRoamingSnapshot();
      } catch (NumberFormatException ignored) {
      }
    }
  }

  private boolean isBestKnownVehicleForRequest(OpenRideRequest openRequest, int selfEtaSeconds) {
    if (openRequest == null || openRequest.payload == null) {
      return true;
    }
    if (!hasRequestPosition(openRequest.payload)) {
      return true;
    }
    if (isForwardedRequest(openRequest.payload)) {
      return true;
    }

    int reqX = Integer.parseInt(openRequest.payload.get(P2PPayloadKeys.REQUEST_X));
    int reqY = Integer.parseInt(openRequest.payload.get(P2PPayloadKeys.REQUEST_Y));

    Set<String> overlayNeighbors = overlayNeighborIdsSnapshot();
    Map<String, Position> knownVehiclePositions = vehiclePositionSnapshot();
    String bestNodeId = descriptor().id();
    int bestEtaSeconds = selfEtaSeconds;

    for (String neighborId : overlayNeighbors) {
      if (neighborId == null || neighborId.isBlank() || neighborId.equals(descriptor().id())) {
        continue;
      }
      Position peerPosition = knownVehiclePositions.get(neighborId);
      if (peerPosition == null) {
        continue;
      }
      int peerEtaSeconds =
          P2PGeoUtils.etaSeconds(
              peerPosition.getX(), peerPosition.getY(), reqX, reqY, cachedAssumedSpeedMps);
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
    cleanupStaleOpenRequests();

    if (isVehicleBusy()) {
      return;
    }
    OpenRideRequest selected = selectOpenRequest();
    if (selected == null) {
      return;
    }
    commitForRequestIfBest(selected, trigger);
  }

  private boolean commitForRequestIfPossible(String requestId) {
    OpenRideRequest request = openRideRequests.get(requestId); // O(1)
    if (request == null) {
      return false;
    }
    if (isVehicleBusy()) {
      // Register seen-but-unserved client position for past-avg-total / past-avg-revisit strategies
      registerRequestForRoaming(request);
      return false;
    }
    return commitForRequestIfBest(request, TRIGGER_INCOMING);
  }

  private OpenRideRequest selectOpenRequest() {
    long nowTick = currentSimulationTick;
    Set<OpenRideRequest> eligibleRequests =
        openRideRequests.values().stream()
            .filter(request -> shouldOffer(request.payload) && shouldReoffer(request, nowTick))
            .collect(Collectors.toCollection(LinkedHashSet::new));
    if (eligibleRequests.isEmpty()) {
      return null;
    }

    String strategyKey =
        P2PRunContext.getProperty(
            P2PSystemProperties.VEHICLE_OPEN_REQUEST_STRATEGY,
            NearestVehicleRequestSelectionStrategy.KEY);
    var strategy = VehicleRequestSelectionStrategies.resolve(strategyKey);

    Optional<VehicleRequestCandidate> selectedCandidate =
        strategy.select(
            eligibleRequests.stream()
                .map(
                    request ->
                        new VehicleRequestCandidate(
                            request.requestId,
                            request.firstSeenAtTick,
                            requestDistanceToVehicle(request),
                            request.payload == null ? Map.of() : request.payload))
                .toList());
    if (selectedCandidate.isEmpty()) {
      return null;
    }

    String selectedRequestId = selectedCandidate.get().requestId();
    return eligibleRequests.stream()
        .filter(request -> request.requestId.equals(selectedRequestId))
        .findFirst()
        .orElse(null);
  }

  private double requestDistanceToVehicle(OpenRideRequest request) {
    if (simulationX == null || simulationY == null || !hasRequestPosition(request.payload)) {
      return Double.MAX_VALUE;
    }
    int reqX = Integer.parseInt(request.payload.get(P2PPayloadKeys.REQUEST_X));
    int reqY = Integer.parseInt(request.payload.get(P2PPayloadKeys.REQUEST_Y));
    return P2PGeoUtils.distance(simulationX, simulationY, reqX, reqY);
  }

  private boolean shouldReoffer(OpenRideRequest openRequest, long nowTick) {
    if (openRequest.lastOfferAtTick == 0L) {
      return true;
    }
    return nowTick - openRequest.lastOfferAtTick >= cachedReofferMinIntervalTicks;
  }

  private void cleanupStaleOpenRequests() {
    long nowTick = currentSimulationTick;
    openRideRequests
        .entrySet()
        .removeIf(entry -> nowTick - entry.getValue().firstSeenAtTick > cachedRequestCacheTtlTicks);
    seenRideRequests
        .entrySet()
        .removeIf(entry -> nowTick - entry.getValue() > cachedRequestCacheTtlTicks);
    roamingRegisteredRideRequests
        .entrySet()
        .removeIf(entry -> nowTick - entry.getValue() >= cachedSeenClientTtlTicks);
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
    if (isVehicleBusy()) {
      return false;
    }

    String forwardedBy = requestPayload.get(P2PPayloadKeys.FORWARDED_BY);
    if (forwardedBy != null && !forwardedBy.isBlank()) {
      return true;
    }
    if (P2PRunContext.getBoolean(P2PSystemProperties.VEHICLE_ALLOW_OUTSIDE_CLIENT_RANGE, false)) {
      return true;
    }

    String searchRadiusValue = requestPayload.get(P2PPayloadKeys.SEARCH_RADIUS);
    if (!hasOwnPosition()
        || !hasRequestPosition(requestPayload)
        || searchRadiusValue == null
        || searchRadiusValue.isBlank()) {
      return true;
    }

    int reqX = Integer.parseInt(requestPayload.get(P2PPayloadKeys.REQUEST_X));
    int reqY = Integer.parseInt(requestPayload.get(P2PPayloadKeys.REQUEST_Y));
    int searchRadius = Math.max(0, Integer.parseInt(searchRadiusValue.trim()));
    return P2PGeoUtils.distance(simulationX, simulationY, reqX, reqY) <= searchRadius;
  }

  private boolean hasOwnPosition() {
    return simulationX != null && simulationY != null;
  }

  private boolean hasRequestPosition(Map<String, String> requestPayload) {
    return requestPayload != null
        && requestPayload.get(P2PPayloadKeys.REQUEST_X) != null
        && requestPayload.get(P2PPayloadKeys.REQUEST_Y) != null;
  }

  private void forwardRideRequest(P2PMessage message, Map<String, String> requestPayload) {
    int incomingHops = parseNonNegativeInt(requestPayload.get(P2PPayloadKeys.HOPS_REMAINING));
    if (incomingHops <= 0) {
      return;
    }

    int nextHops = incomingHops - 1;
    Set<String> overlayNeighbors = overlayNeighborIdsSnapshot();

    String originNodeId =
        requestPayload.getOrDefault(P2PPayloadKeys.ORIGIN_NODE, message.senderId());

    // In embedded mode: forward directly (no P2P messaging overhead)
    if (embeddedMode && peerResolver != null) {
      Map<String, String> forwardedPayload = new LinkedHashMap<>(requestPayload);
      forwardedPayload.put(P2PPayloadKeys.ORIGIN_NODE, originNodeId);
      forwardedPayload.put(P2PPayloadKeys.HOPS_REMAINING, String.valueOf(nextHops));
      forwardedPayload.put(P2PPayloadKeys.FORWARDED_BY, descriptor().id());
      for (String neighborId : overlayNeighbors) {
        if (neighborId.equals(descriptor().id()) || neighborId.equals(message.senderId())) {
          continue;
        }
        VehicleP2PService neighbor = peerResolver.apply(neighborId);
        if (neighbor != null) {
          neighbor.deliverRideRequestDirect(message.requestId(), originNodeId, forwardedPayload);
        }
      }
      return;
    }

    // Non-embedded: use P2P network broadcast with payload cache
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

    publishMessage(
        P2PTopics.RIDE_REQUEST,
        forwardedPayloadStr,
        message.requestId(),
        message.correlationId(),
        node ->
            node.role() == NodeRole.VEHICLE
                && !node.id().equals(descriptor().id())
                && !node.id().equals(message.senderId())
                && overlayNeighbors.contains(node.id()));
  }

  private int parseNonNegativeInt(String value) {
    if (value == null || value.isBlank()) {
      return 0;
    }
    return Math.max(0, Integer.parseInt(value.trim()));
  }

  public Set<String> knownClientIdsSnapshot() {
    cleanupStaleOpenRequests();
    Set<String> knownClientIds = new HashSet<>();
    for (OpenRideRequest request : openRideRequests.values()) {
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

  public synchronized void advanceSimulationTick(long simulationTick) {
    currentSimulationTick = Math.max(currentSimulationTick, simulationTick);
    cleanupStaleOpenRequestsIfNeeded();
  }

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
