package de.sikeller.aqs.p2p.service;

import de.sikeller.aqs.p2p.api.NodeDescriptor;
import de.sikeller.aqs.p2p.api.NodeRole;
import de.sikeller.aqs.p2p.api.P2PMessage;
import de.sikeller.aqs.p2p.api.P2PNetwork;
import java.util.HashMap;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class VehicleP2PService extends AbstractP2PNodeService {
  public static final String TOPIC_RIDE_OFFER = "ride.offer";
  public static final String TOPIC_RIDE_COMMIT = "ride.commit";
  public static final String VEHICLE_OPEN_REQUEST_STRATEGY_PROPERTY =
      "aqs.p2p.vehicle.openRequestStrategy";
  private static final String STRATEGY_GREEDY = "greedy";
  private static final String STRATEGY_NEAREST = "nearest";
  private static final String VEHICLE_COMMIT_LEASE_TICKS_PROPERTY =
      "aqs.p2p.vehicle.commitLeaseTicks";
  private static final String VEHICLE_ASSUMED_SPEED_MPS_PROPERTY = "aqs.p2p.vehicle.assumedSpeedMps";
  private static final String VEHICLE_REBID_MIN_INTERVAL_TICKS_PROPERTY =
      "aqs.p2p.vehicle.rebidMinIntervalTicks";
  private static final String VEHICLE_REBID_MIN_ETA_IMPROVEMENT_PROPERTY = "aqs.p2p.vehicle.rebidMinEtaImprovementSeconds";
  private static final String VEHICLE_REBID_MOVE_DISTANCE_M_PROPERTY = "aqs.p2p.vehicle.rebidMoveDistanceMeters";
  private static final String VEHICLE_REQUEST_CACHE_TTL_TICKS_PROPERTY =
      "aqs.p2p.vehicle.requestCacheTtlTicks";
  private static final long DEFAULT_VEHICLE_COMMIT_LEASE_TICKS = 20L;
  private static final double DEFAULT_ASSUMED_SPEED_MPS = 12.0;
  private static final long DEFAULT_VEHICLE_REBID_MIN_INTERVAL_TICKS = 1L;
  private static final int DEFAULT_VEHICLE_REBID_MIN_ETA_IMPROVEMENT_SECONDS = 5;
  private static final int DEFAULT_VEHICLE_REBID_MOVE_DISTANCE_M = 200;
  private static final long DEFAULT_VEHICLE_REQUEST_CACHE_TTL_TICKS = 120L;

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
  }

  public void sendOffer(String clientNodeId, String offerPayload) {
    sendOffer(clientNodeId, null, offerPayload);
  }

  public void sendOffer(String clientNodeId, String requestId, String offerPayload) {
    if (network().peers().stream().noneMatch(peer -> peer.id().equals(clientNodeId))) {
      log.warn("Client node {} is not part of the network.", clientNodeId);
      return;
    }
    if (requestId == null || requestId.isBlank()) {
      sendTo(clientNodeId, TOPIC_RIDE_OFFER, offerPayload);
      return;
    }
    sendToMessage(clientNodeId, TOPIC_RIDE_OFFER, offerPayload, requestId, requestId);
  }

  public void setSimulationState(boolean available, int positionX, int positionY) {
    setSimulationState(available, positionX, positionY, currentSimulationTick + 1);
  }

  public void setSimulationState(boolean available, int positionX, int positionY, long simulationTick) {
    currentSimulationTick = Math.max(currentSimulationTick, simulationTick);
    boolean becameAvailable = !externallyAvailable && available;
    boolean movedEnough = movedEnoughForRetrigger(positionX, positionY);
    externallyAvailable = available;
    simulationX = positionX;
    simulationY = positionY;

    if (becameAvailable) {
      retriggerOpenRequests("availability");
      return;
    }
    if (available && movedEnough) {
      retriggerOpenRequests("movement");
    }
  }

  @Override
  protected void onMessage(P2PMessage message) {
    log.info(
        "Vehicle node {} received: {} -> {}",
        descriptor().id(),
        message.topic(),
        message.payload());

    if (ClientP2PService.TOPIC_RIDE_REQUEST.equals(message.topic())) {
      handleRideRequest(message);
      return;
    }

    if (ClientP2PService.TOPIC_RIDE_ACCEPT.equals(message.topic())) {
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
    String originNodeId = requestPayload.getOrDefault("originNode", message.senderId());

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
    boolean offeredLocally = offerForRequestIfPossible(requestId);

    if (!seenRideRequests.add(requestId)) {
      return;
    }
    if (!offeredLocally) {
      // If this vehicle cannot execute the request now, pass it exactly one hop to vehicle peers.
      forwardRideRequest(message, requestPayload);
    }
  }

  private boolean offerForOpenRequest(OpenRideRequest openRequest, String trigger) {
    if (openRequest == null || committedByRequest.containsKey(openRequest.requestId)) {
      return false;
    }
    if (!isVehicleAvailable() || !shouldOffer(openRequest.payload)) {
      return false;
    }

    int etaSeconds = estimateEtaSeconds(openRequest.payload);
    if (!shouldReoffer(openRequest, etaSeconds, currentSimulationTick)) {
      return false;
    }

    sendOffer(
        openRequest.originNodeId,
        openRequest.requestId,
        buildOfferPayload(openRequest.requestId, etaSeconds, trigger));
    openRequest.lastOfferAtTick = currentSimulationTick;
    openRequest.lastOfferedEtaSeconds = etaSeconds;
    log.info(
        "Vehicle {} offered requestId={} to origin={} trigger={} etaSeconds={}",
        descriptor().id(),
        openRequest.requestId,
        openRequest.originNodeId,
        trigger,
        etaSeconds);
    return true;
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

  private boolean offerForRequestIfPossible(String requestId) {
    OpenRideRequest request = openRideRequests.get(requestId);
    if (request == null) {
      return false;
    }
    if (!isVehicleAvailable()) {
      return false;
    }
    return offerForOpenRequest(request, "incoming");
  }

  private OpenRideRequest selectOpenRequest() {
    String strategy =
        System.getProperty(VEHICLE_OPEN_REQUEST_STRATEGY_PROPERTY, STRATEGY_NEAREST)
            .trim()
            .toLowerCase(java.util.Locale.ROOT);

    Comparator<OpenRideRequest> comparator =
        STRATEGY_GREEDY.equals(strategy)
            ? Comparator.comparingLong(request -> request.firstSeenAtTick)
            : Comparator.comparingDouble(this::requestDistanceToVehicle)
                .thenComparingLong(request -> request.firstSeenAtTick);

    long nowTick = currentSimulationTick;
    return openRideRequests.values().stream()
        .filter(request -> !committedByRequest.containsKey(request.requestId))
        .sorted(comparator)
        .filter(
            request -> {
              if (!shouldOffer(request.payload)) {
                return false;
              }
              int etaSeconds = estimateEtaSeconds(request.payload);
              return shouldReoffer(request, etaSeconds, nowTick);
            })
        .findFirst()
        .orElse(null);
  }

  private double requestDistanceToVehicle(OpenRideRequest request) {
    Integer reqX = parseCoordinate(request.payload.get("requestX"));
    Integer reqY = parseCoordinate(request.payload.get("requestY"));
    if (reqX == null || reqY == null || simulationX == null || simulationY == null) {
      return Double.MAX_VALUE;
    }
    return distance(simulationX, simulationY, reqX, reqY);
  }

  private boolean shouldReoffer(OpenRideRequest openRequest, int etaSeconds, long nowTick) {
    if (openRequest.lastOfferAtTick == 0L) {
      return true;
    }

    long minIntervalTicks =
        Math.max(
            0L,
            Long.getLong(
                VEHICLE_REBID_MIN_INTERVAL_TICKS_PROPERTY,
                DEFAULT_VEHICLE_REBID_MIN_INTERVAL_TICKS));
    if (nowTick - openRequest.lastOfferAtTick < minIntervalTicks) {
      return false;
    }

    int minImprovementSeconds =
        Math.max(
            0,
            Integer.getInteger(
                VEHICLE_REBID_MIN_ETA_IMPROVEMENT_PROPERTY,
                DEFAULT_VEHICLE_REBID_MIN_ETA_IMPROVEMENT_SECONDS));
    return etaSeconds + minImprovementSeconds <= openRequest.lastOfferedEtaSeconds;
  }

  private boolean movedEnoughForRetrigger(int positionX, int positionY) {
    if (simulationX == null || simulationY == null) {
      return true;
    }
    int minMoveDistance =
        Math.max(
            1,
            Integer.getInteger(
                VEHICLE_REBID_MOVE_DISTANCE_M_PROPERTY, DEFAULT_VEHICLE_REBID_MOVE_DISTANCE_M));
    return distance(simulationX, simulationY, positionX, positionY) >= minMoveDistance;
  }

  private void cleanupStaleOpenRequests() {
    long ttlTicks =
        Math.max(
            1L,
            Long.getLong(
                VEHICLE_REQUEST_CACHE_TTL_TICKS_PROPERTY, DEFAULT_VEHICLE_REQUEST_CACHE_TTL_TICKS));
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
    return true;
  }

  private String buildOfferPayload(String requestId, int etaSeconds, String trigger) {
    Map<String, String> payload = new LinkedHashMap<>();
    payload.put("requestId", requestId);
    payload.put("vehicle", descriptor().id());
    payload.put("etaSeconds", String.valueOf(etaSeconds));
    payload.put("price", "12"); // TODO: Future work - Auction
    payload.put("decision", "vehicle-local-offer");
    payload.put("offerTrigger", trigger);
    return KeyValuePayload.write(payload);
  }

  private int estimateEtaSeconds(Map<String, String> requestPayload) {
    Integer reqX = parseCoordinate(requestPayload.get("requestX"));
    Integer reqY = parseCoordinate(requestPayload.get("requestY"));
    if (reqX == null || reqY == null || simulationX == null || simulationY == null) {
      return 120;
    }

    double speedMps = Math.max(0.1, parseDouble(System.getProperty(VEHICLE_ASSUMED_SPEED_MPS_PROPERTY), DEFAULT_ASSUMED_SPEED_MPS));
    double distance = distance(simulationX, simulationY, reqX, reqY);
    return Math.max(1, (int) Math.round(distance / speedMps));
  }

  private void forwardRideRequest(P2PMessage message, Map<String, String> requestPayload) {
    Map<String, String> forwardedPayload = new LinkedHashMap<>(requestPayload);
    forwardedPayload.put("hopsRemaining", "0");
    forwardedPayload.put("forwardedBy", descriptor().id());

    publishMessage(
        ClientP2PService.TOPIC_RIDE_REQUEST,
        KeyValuePayload.write(forwardedPayload),
        message.requestId(),
        message.correlationId(),
        node ->
            node.role() == NodeRole.VEHICLE
                && !node.id().equals(descriptor().id())
                && !node.id().equals(message.senderId()));

    log.info(
        "Vehicle {} forwarded requestId={} to vehicle peers (remainingHops={})",
        descriptor().id(),
        message.requestId(),
        0);
  }


  private double parseDouble(String value, double defaultValue) {
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    try {
      return Double.parseDouble(value.trim());
    } catch (NumberFormatException ex) {
      return defaultValue;
    }
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

  private double distance(int x1, int y1, int x2, int y2) {
    double dx = x1 - x2;
    double dy = y1 - y2;
    return Math.sqrt(dx * dx + dy * dy);
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
    payload.put("requestId", requestId);
    payload.put("vehicle", descriptor().id());
    payload.put("status", "committed");
    payload.put("decision", "vehicle-accepted");
    long leaseTicks =
        Math.max(
            1L,
            Long.getLong(VEHICLE_COMMIT_LEASE_TICKS_PROPERTY, DEFAULT_VEHICLE_COMMIT_LEASE_TICKS));
    busyUntilTick = currentSimulationTick + leaseTicks;
    openRideRequests.remove(requestId);
    sendToMessage(
        message.senderId(),
        TOPIC_RIDE_COMMIT,
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
      String clientName = request.payload.get("clientName");
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

