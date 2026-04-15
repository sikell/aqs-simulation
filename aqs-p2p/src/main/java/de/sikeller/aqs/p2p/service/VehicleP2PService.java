package de.sikeller.aqs.p2p.service;

import de.sikeller.aqs.p2p.api.NodeDescriptor;
import de.sikeller.aqs.p2p.api.NodeRole;
import de.sikeller.aqs.p2p.api.P2PPayloadKeys;
import de.sikeller.aqs.p2p.api.P2PMessage;
import de.sikeller.aqs.p2p.api.P2PNetwork;
import de.sikeller.aqs.p2p.api.P2PSystemProperties;
import de.sikeller.aqs.p2p.api.P2PTopics;
import de.sikeller.aqs.p2p.service.strategy.NearestVehicleRequestSelectionStrategy;
import de.sikeller.aqs.p2p.service.strategy.VehicleRequestCandidate;
import de.sikeller.aqs.p2p.service.strategy.VehicleRequestSelectionStrategies;
import de.sikeller.aqs.p2p.util.P2PGeoUtils;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class VehicleP2PService extends AbstractP2PNodeService {
  private static final String TRIGGER_AVAILABILITY = "availability";
  private static final String TRIGGER_MOVEMENT = "movement";
  private static final String TRIGGER_INCOMING = "incoming";
  private static final long DEFAULT_VEHICLE_COMMIT_LEASE_TICKS = 20L;
  private static final double DEFAULT_ASSUMED_SPEED_MPS = 12.0;
  private static final long DEFAULT_VEHICLE_REOFFER_MIN_INTERVAL_TICKS = 1L;
  private static final int DEFAULT_VEHICLE_REOFFER_MIN_ETA_IMPROVEMENT_SECONDS = 5;
  private static final int DEFAULT_VEHICLE_REOFFER_MOVE_DISTANCE_M = 200;
  private static final long DEFAULT_VEHICLE_REQUEST_CACHE_TTL_TICKS = 120L;

  private final NodeDescriptor nodeDescriptor;
  private final ConcurrentMap<String, String> committedByRequest = new ConcurrentHashMap<>();
  private final Set<String> seenRideRequests = ConcurrentHashMap.newKeySet();
  private final ConcurrentMap<String, OpenRideRequest> openRideRequests = new ConcurrentHashMap<>();
  private volatile long busyUntilTick;
  private volatile long currentSimulationTick;
  private volatile boolean externallyAvailable = true;
  private volatile Integer simulationX;
  private volatile Integer simulationY;

  public VehicleP2PService(String nodeId, P2PNetwork network) {
    super(new NodeDescriptor(nodeId, NodeRole.VEHICLE), network);
    this.nodeDescriptor = new NodeDescriptor(nodeId, NodeRole.VEHICLE);
  }

  @Override
  public NodeDescriptor descriptor() {
    return nodeDescriptor;
  }

  public void sendOffer(String clientNodeId, String requestId, String offerPayload) {
    if (network().peers().stream().noneMatch(peer -> peer.id().equals(clientNodeId))) {
      log.warn("Client node {} not a peer.", clientNodeId);
      return;
    }
    sendToMessage(clientNodeId, P2PTopics.RIDE_OFFER, offerPayload, requestId, requestId);
  }

  public synchronized void setSimulationState(boolean available, int positionX, int positionY) {
    setSimulationState(available, positionX, positionY, currentSimulationTick + 1);
  }

  public synchronized void setSimulationState(boolean available, int positionX, int positionY, long simulationTick) {
    currentSimulationTick = Math.max(currentSimulationTick, simulationTick);
    boolean becameAvailable = !externallyAvailable && available;
    boolean movedEnough = movedEnoughForRetrigger(positionX, positionY);
    externallyAvailable = available;
    simulationX = positionX;
    simulationY = positionY;
    updateVehiclePositionSnapshot(positionX, positionY, currentSimulationTick);

    if (becameAvailable) {
      retriggerOpenRequests(TRIGGER_AVAILABILITY);
      return;
    }
    if (available && movedEnough) {
      retriggerOpenRequests(TRIGGER_MOVEMENT);
    }
  }

  @Override
  protected void onMessage(P2PMessage message) {
    log.info(
        "Vehicle node {} received: {} -> {}",
        descriptor().id(),
        message.topic(),
        message.payload());

    if (P2PTopics.RIDE_REQUEST.equals(message.topic())) {
      handleRideRequest(message);
      return;
    }

    if (P2PTopics.RIDE_ACCEPT.equals(message.topic())) {
      handleAccept(message);
    }
  }

  private void handleRideRequest(P2PMessage message) {
    String requestId = message.requestId();
    if (requestId == null || requestId.isBlank()) {
      return;
    }

    cleanupStaleOpenRequests();
    Map<String, String> requestPayload = KeyValuePayload.parse(message.payload());
    String originNodeId = requestPayload.getOrDefault(P2PPayloadKeys.ORIGIN_NODE, message.senderId());

    openRideRequests.compute(
        requestId,
        (id, existing) -> {
          if (existing == null) {
            return new OpenRideRequest(id, originNodeId, requestPayload, currentSimulationTick);
          }
          existing.originNodeId = originNodeId;
          existing.payload = new HashMap<>(requestPayload);
          return existing;
        });
    offerForRequestIfPossible(requestId);

    if (!seenRideRequests.add(requestId)) {
      return;
    }
    // Forward first-seen requests regardless of local offer to improve decentralized visibility.
    forwardRideRequest(message, requestPayload);
  }

  private void offerForOpenRequest(OpenRideRequest openRequest, String trigger) {
    if (openRequest == null || committedByRequest.containsKey(openRequest.requestId)) {
      return;
    }
    if (!isVehicleAvailable() || !shouldOffer(openRequest.payload)) {
      return;
    }

    int etaSeconds = estimateEtaSeconds(openRequest.payload);
    if (!shouldReoffer(openRequest, etaSeconds, currentSimulationTick)) {
      return;
    }
    if (!isBestKnownVehicleForRequest(openRequest, etaSeconds)) {
      return;
    }

    sendOffer(
        openRequest.originNodeId,
        openRequest.requestId,
        buildOfferPayload(openRequest.requestId, etaSeconds));
    openRequest.lastOfferAtTick = currentSimulationTick;
    openRequest.lastOfferedEtaSeconds = etaSeconds;
    log.info(
        "Vehicle {} offered requestId={} to origin={} trigger={} etaSeconds={}",
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

    Integer reqX = parseCoordinate(openRequest.payload.get(P2PPayloadKeys.REQUEST_X));
    Integer reqY = parseCoordinate(openRequest.payload.get(P2PPayloadKeys.REQUEST_Y));
    if (reqX == null || reqY == null) {
      return true;
    }

    Set<String> overlayNeighbors = overlayNeighborIdsSnapshot();
    Map<String, int[]> knownVehiclePositions = vehiclePositionSnapshot();
    String bestNodeId = descriptor().id();
    int bestEtaSeconds = selfEtaSeconds;

    for (NodeDescriptor peer : network().peers()) {
      if (peer.role() != NodeRole.VEHICLE || peer.id().equals(descriptor().id())) {
        continue;
      }
      if (!overlayNeighbors.contains(peer.id())) {
        continue;
      }

      int[] peerPosition = knownVehiclePositions.get(peer.id());
      if (peerPosition == null || peerPosition.length < 2) {
        continue;
      }

      int peerEtaSeconds = estimateEtaSecondsFromPosition(peerPosition[0], peerPosition[1], reqX, reqY);
      if (peerEtaSeconds < bestEtaSeconds
          || (peerEtaSeconds == bestEtaSeconds && peer.id().compareTo(bestNodeId) < 0)) {
        bestEtaSeconds = peerEtaSeconds;
        bestNodeId = peer.id();
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
    offerForSelectedOpenRequest(trigger);
  }

  private void offerForSelectedOpenRequest(String trigger) {
    if (openRideRequests.isEmpty()) {
      return;
    }
    if (!isVehicleAvailable()) {
      return;
    }

    OpenRideRequest selected = selectOpenRequest();
    if (selected == null) {
      return;
    }
    offerForOpenRequest(selected, trigger);
  }

  private void offerForRequestIfPossible(String requestId) {
    OpenRideRequest request = openRideRequests.get(requestId);
    if (request == null) {
      return;
    }
    if (!isVehicleAvailable()) {
      return;
    }
    offerForOpenRequest(request, TRIGGER_INCOMING);
  }

  private OpenRideRequest selectOpenRequest() {
    long nowTick = currentSimulationTick;
    Set<OpenRideRequest> eligibleRequests =
        openRideRequests.values().stream()
        .filter(request -> !committedByRequest.containsKey(request.requestId))
        .filter(
            request -> {
              if (!shouldOffer(request.payload)) {
                return false;
              }
              int etaSeconds = estimateEtaSeconds(request.payload);
              return shouldReoffer(request, etaSeconds, nowTick);
            })
        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    if (eligibleRequests.isEmpty()) {
      return null;
    }

    String strategyKey = System.getProperty(P2PSystemProperties.VEHICLE_OPEN_REQUEST_STRATEGY, NearestVehicleRequestSelectionStrategy.KEY);
    var strategy = VehicleRequestSelectionStrategies.resolve(strategyKey);

    Optional<VehicleRequestCandidate> selectedCandidate =
        strategy.select(eligibleRequests.stream().map(this::toCandidate).toList());
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
        request.payload == null ? Map.of() : Map.copyOf(request.payload));
  }

  private double requestDistanceToVehicle(OpenRideRequest request) {
    Integer reqX = parseCoordinate(request.payload.get(P2PPayloadKeys.REQUEST_X));
    Integer reqY = parseCoordinate(request.payload.get(P2PPayloadKeys.REQUEST_Y));
    if (reqX == null || reqY == null || simulationX == null || simulationY == null) {
      return Double.MAX_VALUE;
    }
    return P2PGeoUtils.distance(simulationX, simulationY, reqX, reqY);
  }

  private boolean shouldReoffer(OpenRideRequest openRequest, int etaSeconds, long nowTick) {
    if (openRequest.lastOfferAtTick == 0L) {
      return true;
    }

    long minIntervalTicks = Math.max(0L, resolveReofferMinIntervalTicks());
    if (nowTick - openRequest.lastOfferAtTick < minIntervalTicks) {
      return false;
    }

    int minImprovementSeconds = Math.max(0, resolveReofferMinEtaImprovementSeconds());
    return etaSeconds + minImprovementSeconds <= openRequest.lastOfferedEtaSeconds;
  }

  private boolean movedEnoughForRetrigger(int positionX, int positionY) {
    if (simulationX == null || simulationY == null) {
      return true;
    }
    int minMoveDistance = Math.max(1, resolveReofferMoveDistanceMeters());
    return P2PGeoUtils.distance(simulationX, simulationY, positionX, positionY) >= minMoveDistance;
  }

  private void cleanupStaleOpenRequests() {
    long ttlTicks =
        Math.max(
            1L,
            Long.getLong(
                P2PSystemProperties.VEHICLE_REQUEST_CACHE_TTL_TICKS,
                DEFAULT_VEHICLE_REQUEST_CACHE_TTL_TICKS));
    long nowTick = currentSimulationTick;
    openRideRequests.entrySet().removeIf(entry -> nowTick - entry.getValue().firstSeenAtTick > ttlTicks);
  }

  private boolean isVehicleAvailable() {
    return externallyAvailable && currentSimulationTick >= busyUntilTick;
  }

  private boolean shouldOffer(Map<String, String> requestPayload) {
    if (requestPayload == null) {
      return true;
    }
    if (Boolean.parseBoolean(System.getProperty(P2PSystemProperties.VEHICLE_ALLOW_OUTSIDE_CLIENT_RANGE, "false"))) {
      return true;
    }

    // k-hop expansion: forwarded requests are intentionally serviceable by overlay neighbors.
    String forwardedBy = requestPayload.get(P2PPayloadKeys.FORWARDED_BY);
    if (forwardedBy != null && !forwardedBy.isBlank()) {
      return true;
    }


    Integer reqX = parseCoordinate(requestPayload.get(P2PPayloadKeys.REQUEST_X));
    Integer reqY = parseCoordinate(requestPayload.get(P2PPayloadKeys.REQUEST_Y));
    Integer searchRadius = parseNonNegativeIntOrNull(requestPayload.get(P2PPayloadKeys.SEARCH_RADIUS));
    if (reqX == null || reqY == null || searchRadius == null || simulationX == null || simulationY == null) {
      return true;
    }

    boolean inRange = P2PGeoUtils.distance(simulationX, simulationY, reqX, reqY) <= searchRadius;
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

  private String buildOfferPayload(String requestId, int etaSeconds) {
    Map<String, String> payload = new LinkedHashMap<>();
    payload.put(P2PPayloadKeys.REQUEST_ID, requestId);
    payload.put(P2PPayloadKeys.VEHICLE, descriptor().id());
    payload.put(P2PPayloadKeys.ETA_SECONDS, String.valueOf(etaSeconds));
    return KeyValuePayload.write(payload);
  }

  private int estimateEtaSeconds(Map<String, String> requestPayload) {
    Integer reqX = parseCoordinate(requestPayload.get(P2PPayloadKeys.REQUEST_X));
    Integer reqY = parseCoordinate(requestPayload.get(P2PPayloadKeys.REQUEST_Y));
    if (reqX == null || reqY == null || simulationX == null || simulationY == null) {
      return 120;
    }

    return estimateEtaSecondsFromPosition(simulationX, simulationY, reqX, reqY);
  }

  private int estimateEtaSecondsFromPosition(int startX, int startY, int reqX, int reqY) {
    double speedMps =
        Math.max(
            0.1,
            parseDouble(System.getProperty(P2PSystemProperties.VEHICLE_ASSUMED_SPEED_MPS)));
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
    long targetCount =
        network().peers().stream()
            .filter(node -> node.role() == NodeRole.VEHICLE)
            .filter(node -> !node.id().equals(descriptor().id()))
            .filter(node -> !node.id().equals(message.senderId()))
            .filter(node -> overlayNeighbors.contains(node.id()))
            .count();

    Map<String, String> forwardedPayload = new LinkedHashMap<>(requestPayload);
    forwardedPayload.put(P2PPayloadKeys.HOPS_REMAINING, String.valueOf(nextHops));
    forwardedPayload.put(P2PPayloadKeys.FORWARDED_BY, descriptor().id());

    publishMessage(
        P2PTopics.RIDE_REQUEST,
        KeyValuePayload.write(forwardedPayload),
        message.requestId(),
        message.correlationId(),
        node ->
            node.role() == NodeRole.VEHICLE
                && !node.id().equals(descriptor().id())
                && !node.id().equals(message.senderId()));

    log.info(
        "Vehicle {} forwarded requestId={} to vehicle peers (incomingHops={} nextHops={} targets={})",
        descriptor().id(),
        message.requestId(),
        incomingHops,
        nextHops,
        targetCount);
  }


  private double parseDouble(String value) {
    if (value == null || value.isBlank()) {
      return DEFAULT_ASSUMED_SPEED_MPS;
    }
    try {
      return Double.parseDouble(value.trim());
    } catch (NumberFormatException ex) {
      return DEFAULT_ASSUMED_SPEED_MPS;
    }
  }

  private int parseNonNegativeInt(String value) {
    if (value == null || value.isBlank()) {
      return 0;
    }
    try {
      return Math.max(0, Integer.parseInt(value.trim()));
    } catch (NumberFormatException ex) {
      return 0;
    }
  }

  private Integer parseNonNegativeIntOrNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Math.max(0, Integer.parseInt(value.trim()));
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private long resolveReofferMinIntervalTicks() {
    return Long.getLong(
        P2PSystemProperties.VEHICLE_REOFFER_MIN_INTERVAL_TICKS,
        DEFAULT_VEHICLE_REOFFER_MIN_INTERVAL_TICKS);
  }

  private int resolveReofferMinEtaImprovementSeconds() {
    return Integer.getInteger(
        P2PSystemProperties.VEHICLE_REOFFER_MIN_ETA_IMPROVEMENT_SECONDS,
        DEFAULT_VEHICLE_REOFFER_MIN_ETA_IMPROVEMENT_SECONDS);
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
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException ex) {
      return null;
    }
  }



  private void handleAccept(P2PMessage message) {
    String requestId = message.requestId();
    if (!requestId.equals(message.correlationId())) {
      log.warn(
          "Ignoring accept with mismatching correlationId requestId={} correlationId={}",
          requestId,
          message.correlationId());
      return;
    }

    if (!isVehicleAvailable()) {
      log.info(
          "Ignoring accept while vehicle is already busy requestId={} sender={} busyUntilTick={} nowTick={}",
          requestId,
          message.senderId(),
          busyUntilTick,
          currentSimulationTick);
      return;
    }

    OpenRideRequest openRequest = openRideRequests.get(requestId);
    if (openRequest != null && !shouldOffer(openRequest.payload)) {
      log.info(
          "Ignoring accept for out-of-range requestId={} sender={}",
          requestId,
          message.senderId());
      return;
    }

    String existingWinner = committedByRequest.putIfAbsent(requestId, descriptor().id());
    if (existingWinner != null && !existingWinner.equals(descriptor().id())) {
      log.info(
          "Ignoring accept for already committed requestId={} winner={}", requestId, existingWinner);
      return;
    }
    if (existingWinner != null) {
      log.info("Ignoring duplicate accept for already committed requestId={}", requestId);
      return;
    }

    Map<String, String> payload = new LinkedHashMap<>();
    payload.put(P2PPayloadKeys.REQUEST_ID, requestId);
    payload.put(P2PPayloadKeys.VEHICLE, descriptor().id());
    long leaseTicks =
        Math.max(
            1L,
            Long.getLong(P2PSystemProperties.VEHICLE_COMMIT_LEASE_TICKS, DEFAULT_VEHICLE_COMMIT_LEASE_TICKS));
    busyUntilTick = currentSimulationTick + leaseTicks;
    openRideRequests.remove(requestId);
    sendToMessage(
        message.senderId(),
        P2PTopics.RIDE_COMMIT,
        KeyValuePayload.write(payload),
        requestId,
        requestId);
    log.info("Committed requestId={} for collector/client={}", requestId, message.senderId());
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

  private static class OpenRideRequest {
    private final String requestId;
    private volatile String originNodeId;
    private volatile Map<String, String> payload;
    private final long firstSeenAtTick;
    private volatile long lastOfferAtTick;
    private volatile int lastOfferedEtaSeconds = Integer.MAX_VALUE;

    private OpenRideRequest(
        String requestId,
        String originNodeId,
        Map<String, String> payload,
        long firstSeenAtTick) {
      this.requestId = requestId;
      this.originNodeId = originNodeId;
      this.payload = new HashMap<>(payload);
      this.firstSeenAtTick = firstSeenAtTick;
    }
  }

}

