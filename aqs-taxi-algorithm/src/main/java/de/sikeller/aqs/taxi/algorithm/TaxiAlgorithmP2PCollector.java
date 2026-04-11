package de.sikeller.aqs.taxi.algorithm;

import de.sikeller.aqs.model.AlgorithmParameter;
import de.sikeller.aqs.model.AlgorithmResult;
import de.sikeller.aqs.model.Client;
import de.sikeller.aqs.model.P2PNetworkEdgeSnapshot;
import de.sikeller.aqs.model.P2PNetworkNodeSnapshot;
import de.sikeller.aqs.model.P2PNetworkSnapshot;
import de.sikeller.aqs.model.P2PStatusProvider;
import de.sikeller.aqs.model.SimulationConfiguration;
import de.sikeller.aqs.model.TargetList;
import de.sikeller.aqs.model.Taxi;
import de.sikeller.aqs.model.World;
import de.sikeller.aqs.p2p.api.NodeDescriptor;
import de.sikeller.aqs.p2p.api.NodeRole;
import de.sikeller.aqs.p2p.api.P2PMessage;
import de.sikeller.aqs.p2p.api.P2PNetwork;
import de.sikeller.aqs.p2p.api.P2PSystemProperties;
import de.sikeller.aqs.p2p.api.P2PTopics;
import de.sikeller.aqs.p2p.service.ClientP2PService;
import de.sikeller.aqs.p2p.service.VehicleP2PService;
import de.sikeller.aqs.p2p.service.KeyValuePayload;
import de.sikeller.aqs.p2p.service.strategy.GreedyVehicleRequestSelectionStrategy;
import de.sikeller.aqs.p2p.service.strategy.NearestVehicleRequestSelectionStrategy;
import de.sikeller.aqs.p2p.transport.inmemory.InMemoryP2PNetwork;
import de.sikeller.aqs.p2p.transport.network.LanP2PNetwork;
import de.sikeller.aqs.taxi.algorithm.distributed.rqs.RangeQuerySystem;
import de.sikeller.aqs.taxi.algorithm.distributed.rqs.SimulatedRangeQuerySystem;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.function.Predicate;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * P2P-Collector im Algorithmus-Modul, damit er vom Algorithmus-Scanner gefunden wird.
 */
@Slf4j
@Getter
public class TaxiAlgorithmP2PCollector extends AbstractTaxiAlgorithm implements P2PStatusProvider {

  private final String name = "P2PCollector";
  private Map<String, Integer> parameters = new HashMap<>();

  @Override
  public void setParameters(Map<String, Integer> parameters) {
    this.parameters = parameters == null ? new HashMap<>() : new HashMap<>(parameters);
    rangeQuerySystem.setParameters(this.parameters);
    applyVehicleDispatchConfig(this.parameters);
  }

  private P2PNetwork network;
  private ClientP2PService clientNode;
  private final Map<String, VehicleP2PService> localVehicleNodesByTaxiName = new HashMap<>();
  private int processedInboxMessages = 0;
  private long stepCounter = 0;
  private final Map<String, PendingRequest> pendingByRequestId = new HashMap<>();
  private final Map<String, String> requestIdByClientName = new HashMap<>();
  private final Map<String, Set<String>> taxiKnowledgeByClientIds = new ConcurrentHashMap<>();
  private final Map<String, String> vehicleNodeToTaxiName = new HashMap<>();
  private final Map<String, String> taxiNameToVehicleNodeId = new HashMap<>();
  private final Map<String, String> p2pStatus = new ConcurrentHashMap<>();
  private final Map<String, TopologyPeerView> topologyViewsByNodeId = new ConcurrentHashMap<>();
  private final RangeQuerySystem rangeQuerySystem = new SimulatedRangeQuerySystem();
  private long commitRoundRobinCursor = 0;
  private long lastTopologyScanAtStep = Long.MIN_VALUE;
  private String lastTopologyScanId = "";

  private static final String P2P_OFFER_COLLECTION_TICKS = "p2pOfferCollectionTicks";
  private static final String P2P_TOPOLOGY_SCAN_TICKS = "p2pTopologyScanTicks";
  private static final String P2P_REQUEST_REPUBLISH_TICKS = "p2pRequestRepublishTicks";
  private static final String P2P_TCP_PORT = "p2pTcpPort";
  private static final String P2P_DISCOVERY_PORT = "p2pDiscoveryPort";
  private static final String P2P_MULTICAST_A = "p2pMulticastA";
  private static final String P2P_MULTICAST_B = "p2pMulticastB";
  private static final String P2P_MULTICAST_C = "p2pMulticastC";
  private static final String P2P_MULTICAST_D = "p2pMulticastD";
  private static final String P2P_DISCOVERY_WAIT_MS = "p2pDiscoveryWaitMs";
  private static final String P2P_REQUEST_FORWARD_HOPS = "p2pRequestForwardHops";
  private static final String P2P_FIXED_SEARCH_RADIUS = "p2pFixedSearchRadius";
  private static final String P2P_OVERLAY_MAX_NEIGHBORS = "p2pOverlayMaxNeighbors";
  private static final String P2P_OVERLAY_SHORTCUTS = "p2pOverlayShortcuts";
  private static final String CALCULATE_FULL_TAXIS = "CalculateFullTaxis";
  private static final String UNKNOWN_ROLE = "UNKNOWN";
  private static final String EMBEDDED_MODE_PROPERTY = "p2pEmbeddedSimulation";
  private static final String P2P_VEHICLE_OPEN_CLIENT_STRATEGY = "p2pVehicleOpenClientStrategy";
  private static final String P2P_RQS_ROUTE_PROXIMITY_MODE = "p2pRqsRouteProximityMode";
  private static final String P2P_VEHICLE_COMMIT_LEASE_TICKS = "p2pVehicleCommitLeaseTicks";
  private static final String P2P_VEHICLE_REBID_INTERVAL_TICKS = "p2pVehicleRebidIntervalTicks";
  private static final String P2P_VEHICLE_REQUEST_CACHE_TTL_TICKS = "p2pVehicleRequestCacheTtlTicks";
  private static final String PAYLOAD_SIM_TICK = "simTick";
  private static final String PAYLOAD_CLIENT_NAME = "clientName";
  private static final String PAYLOAD_REQUEST_X = "requestX";
  private static final String PAYLOAD_REQUEST_Y = "requestY";
  private static final String PAYLOAD_TARGET_X = "targetX";
  private static final String PAYLOAD_TARGET_Y = "targetY";
  private static final String PAYLOAD_SEARCH_RADIUS = "searchRadius";
  private static final String PAYLOAD_SCOPE = "scope";
  private static final String PAYLOAD_NODE = "node";
  private static final String PAYLOAD_ROLE = "role";
  private static final String PAYLOAD_NEIGHBORS = "neighbors";
  private static final String PAYLOAD_SHORTCUT_NEIGHBORS = "shortcutNeighbors";
  private static final String PAYLOAD_VEHICLE = "vehicle";
  private static final String PAYLOAD_ETA_SECONDS = "etaSeconds";
  private static final String SCOPE_RQS_SEEDED = "rqs-seeded";
  private static final String STATUS_MODE = "mode";
  private static final String STATUS_COLLECTOR_NODE = "collectorNode";
  private static final String STATUS_KNOWN_PEERS = "knownPeers";
  private static final String STATUS_VEHICLE_PEERS = "vehiclePeers";
  private static final String STATUS_PENDING_REQUESTS = "pendingRequests";
  private static final String STATUS_MAPPED_VEHICLES = "mappedVehicles";
  private static final String STATUS_TOPOLOGY_VIEWS = "topologyViews";
  private static final String STATUS_TOPOLOGY_SCAN_ID = "topologyScanId";
  private static final String STATUS_OVERLAY_MODE = "overlayMode";
  private static final String STATUS_OVERLAY_MAX_NEIGHBORS = "overlayMaxNeighbors";
  private static final String STATUS_OVERLAY_SHORTCUTS = "overlayShortcuts";
  private static final String STATUS_RQS_FIXED_RADIUS = "rqsFixedRadius";
  private static final String STATUS_RQS_CENTER = "rqsCenter";
  private static final String STATUS_RQS_RECOGNITION_MODE = "rqsRecognitionMode";
  private static final String STATUS_VEHICLE_CLIENT_SELECTION = "vehicleClientSelection";
  private static final String STATUS_CLIENT_RANGE_FILTER = "clientRangeFilter";
  private static final String STATUS_RUNTIME_MODE = "runtimeMode";
  private static final String STATUS_LOCAL_VEHICLE_NODES = "localVehicleNodes";
  private static final String STATUS_LAST_EVENT = "lastEvent";
  private static final String VALUE_P2P = "P2P";
  private static final String VALUE_UNKNOWN = "-";
  private static final String VALUE_SMALL_WORLD = "SMALL_WORLD";
  private static final String VALUE_CLIENT = "client";
  private static final String VALUE_TAXI_POSITION = "taxi-position";
  private static final String VALUE_TAXI_ROUTE = "taxi-route";
  private static final String VALUE_SEED_ONLY = "seed-only";
  private static final String VALUE_EMBEDDED = "EMBEDDED";
  private static final String VALUE_LAN = "LAN";
  private static final String DEFAULT_OVERLAY_MAX_NEIGHBORS = "3";
  private static final String DEFAULT_OVERLAY_SHORTCUTS = "1";
  private static final String KEY_TAXI_COUNT = "taxiCount";
  private static final String NODE_ID_COLLECTOR_LOCAL = "sim-collector-local";
  private static final String NODE_ID_COLLECTOR_PREFIX = "sim-collector-";
  private static final String NODE_ID_VEHICLE_PREFIX = "vehicle-";
  private static final String TAXI_NAME_PREFIX = "t";
  private static final String EDGE_KEY_SEPARATOR = "\u0000";
  private static final String EVENT_COLLECTOR_INITIALIZED = "collector-initialized";
  private static final String EVENT_WAITING_FOR_COMMIT = "waiting-for-commit";
  private static final String EVENT_REQUEST_PUBLISHED_PREFIX = "request-published-";
  private static final String EVENT_TOPOLOGY_RESPONSE_PREFIX = "topology-response-";
  private static final String EVENT_TOPOLOGY_SCAN_REQUESTED_MANUAL = "topology-scan-requested-manual";
  private static final String EVENT_DERIVED_TAXI_COUNT_PREFIX = "derived-taxiCount-";
  private static final String EVENT_ASSIGNED_PREFIX = "assigned-";
  private static final String EVENT_COMMIT_PREFIX = "commit-";
  private static final String EVENT_OFFER_TRACKED_PREFIX = "offer-tracked-";
  private static final int VEHICLE_STRATEGY_CODE_GREEDY = 0;
  private static final int VEHICLE_STRATEGY_CODE_NEAREST = 1;
  private String collectorNodeId = "";

  @Override
  public SimulationConfiguration getParameters() {
    return new SimulationConfiguration(
        new AlgorithmParameter(P2P_TCP_PORT, 46100),
        new AlgorithmParameter(P2P_DISCOVERY_PORT, 45892),
        new AlgorithmParameter(P2P_MULTICAST_A, 239),
        new AlgorithmParameter(P2P_MULTICAST_B, 255),
        new AlgorithmParameter(P2P_MULTICAST_C, 42),
        new AlgorithmParameter(P2P_MULTICAST_D, 99),
        new AlgorithmParameter(P2P_DISCOVERY_WAIT_MS, 6000),
        new AlgorithmParameter(P2P_OFFER_COLLECTION_TICKS, 0),
        new AlgorithmParameter(P2P_TOPOLOGY_SCAN_TICKS, 20),
        new AlgorithmParameter(P2P_REQUEST_FORWARD_HOPS, 2),
        new AlgorithmParameter(P2P_REQUEST_REPUBLISH_TICKS, 3),
        new AlgorithmParameter(P2P_FIXED_SEARCH_RADIUS, 5000),
        new AlgorithmParameter(P2P_RQS_ROUTE_PROXIMITY_MODE, 0),
        new AlgorithmParameter(P2P_VEHICLE_COMMIT_LEASE_TICKS, 20),
        new AlgorithmParameter(P2P_VEHICLE_REBID_INTERVAL_TICKS, 1),
        new AlgorithmParameter(P2P_VEHICLE_REQUEST_CACHE_TTL_TICKS, 120),
        new AlgorithmParameter(CALCULATE_FULL_TAXIS, 0),
        new AlgorithmParameter(P2P_VEHICLE_OPEN_CLIENT_STRATEGY, 1),
        new AlgorithmParameter(EMBEDDED_MODE_PROPERTY, 1),
        new AlgorithmParameter(P2P_OVERLAY_MAX_NEIGHBORS, 3),
        new AlgorithmParameter(P2P_OVERLAY_SHORTCUTS, 1));
  }

  @Override
  public Map<String, Integer> prepareWorldParameters(Map<String, Integer> inputParameters) {
    Map<String, Integer> prepared = new HashMap<>(inputParameters);
    setParameters(prepared);
    applyOverlayConfig(prepared);
    // Recreate collector node for fresh config on every simulation init.
    stopNetworkNode();
    ensureCollectorNode(prepared);
    if (!isEmbeddedSimulationMode(prepared)) {
      deriveTaxiCountFromNetwork(prepared);
    }
    return prepared;
  }

  @Override
  public void init(World world) {
    processedInboxMessages = 0;
    stepCounter = 0;
    commitRoundRobinCursor = 0;
    pendingByRequestId.clear();
    requestIdByClientName.clear();
    taxiKnowledgeByClientIds.clear();
    topologyViewsByNodeId.clear();
    lastTopologyScanAtStep = Long.MIN_VALUE;
    lastTopologyScanId = "";

    ensureCollectorNode(parameters);
    rangeQuerySystem.setParameters(parameters);
    if (isEmbeddedSimulationMode(parameters)) {
      ensureLocalVehicleNodes(world);
      syncLocalVehicleStates(world);
    }
    requestTopologyScanIfDue(true);
    refreshStatus(EVENT_COLLECTOR_INITIALIZED);
  }

  @Override
  protected AlgorithmResult nextStep(World world, Collection<Client> waitingClients) {
    stepCounter++;
    cleanupNoLongerWaiting(waitingClients);
    requestTopologyScanIfDue(false);

    if (clientNode != null) {
      if (isEmbeddedSimulationMode()) {
        ensureLocalVehicleNodes(world);
        syncLocalVehicleStates(world);
      }
      retriggerRequestsIfNeeded(waitingClients);
      publishNewRequests(world, waitingClients);

      var inbox = clientNode.inboxSnapshot();
      int newMessages = Math.max(0, inbox.size() - processedInboxMessages);
      if (newMessages > 0) {
        List<P2PMessage> delta = inbox.subList(processedInboxMessages, inbox.size());
        long newOffers = delta.stream().filter(m -> P2PTopics.RIDE_OFFER.equals(m.topic())).count();
        handleIncoming(delta);
        log.info(
            "[P2P-COLLECTOR] event t={} peers={} waiting={} newMessages={} newOffers={} inbox={}",
            world.getCurrentTime(),
            network.peers().size(),
            waitingClients.size(),
            newMessages,
            newOffers,
            inbox.size());
        processedInboxMessages = inbox.size();
      }

      // Collector observes only; no central dispatch decision is made here.

      if (stepCounter % 20 == 0) {
        log.info(
            "[P2P-COLLECTOR] status t={} waiting={} {}",
            world.getCurrentTime(),
            waitingClients.size(),
            clientNode.runtimeStatus().asLogLine());
      }
    }

    if (waitingClients.isEmpty()) {
      return ok();
    }

    List<Client> committedClients =
        waitingClients.stream()
            .filter(
                client -> {
                  String requestId = requestIdByClientName.get(client.getName());
                  if (requestId == null) {
                    return false;
                  }
                  PendingRequest pending = pendingByRequestId.get(requestId);
                  return pending != null && pending.committedVehicleNodeId != null;
                })
            .toList();

    if (!committedClients.isEmpty()) {
      int startIndex = Math.floorMod(commitRoundRobinCursor, committedClients.size());
      commitRoundRobinCursor++;
      for (int attempt = 0; attempt < committedClients.size(); attempt++) {
        Client client = committedClients.get((startIndex + attempt) % committedClients.size());
        PendingRequest pending = pendingByRequestId.get(requestIdByClientName.get(client.getName()));
        if (pending == null || pending.committedVehicleNodeId == null) {
          continue;
        }
        Taxi selectedTaxi = selectTaxiForCommit(world, pending.committedVehicleNodeId);
        if (selectedTaxi == null) {
          continue;
        }

        world.mutate().planClientForTaxi(selectedTaxi, client, TargetList.sequentialOrders);
        log.info(
            "[P2P-COLLECTOR] assigned committed requestId={} client={} vehicle={}",
            pending.requestId,
            client.getName(),
            pending.committedVehicleNodeId);
        removePendingForClient(client.getName());
        refreshStatus(EVENT_ASSIGNED_PREFIX + pending.requestId);
        return ok();
      }
    }
    refreshStatus(EVENT_WAITING_FOR_COMMIT);
    return ok();
  }

  private void publishNewRequests(World world, Collection<Client> waitingClients) {
    for (Client client : waitingClients) {
      if (requestIdByClientName.containsKey(client.getName())) {
        continue;
      }

      double searchRadius = resolveSearchRadius();
      Set<String> rqsVehicleNodeIds = resolveRqsVehicleNodeIds(world, client, searchRadius);
      if (rqsVehicleNodeIds.isEmpty()) {
        log.info(
            "[P2P-COLLECTOR] skipped publish client={} reason=no-rqs-vehicles searchRadius={}",
            client.getName(),
            Math.round(searchRadius));
        continue;
      }
      rqsVehicleNodeIds.forEach(vehicleNodeId -> registerTaxiKnowledge(vehicleNodeId, client.getName()));
      Predicate<NodeDescriptor> effectiveFilter =
          node -> node.role() == NodeRole.VEHICLE && rqsVehicleNodeIds.contains(node.id());
      int requestForwardHops = Math.max(0, parameters.getOrDefault(P2P_REQUEST_FORWARD_HOPS, 2));

      Map<String, String> extraPayload = new LinkedHashMap<>();
      extraPayload.put(PAYLOAD_SIM_TICK, String.valueOf(stepCounter));
      extraPayload.put(PAYLOAD_CLIENT_NAME, client.getName());
      extraPayload.put(PAYLOAD_REQUEST_X, String.valueOf(client.getPosition().getX()));
      extraPayload.put(PAYLOAD_REQUEST_Y, String.valueOf(client.getPosition().getY()));
      extraPayload.put(PAYLOAD_TARGET_X, String.valueOf(client.getTarget().getX()));
      extraPayload.put(PAYLOAD_TARGET_Y, String.valueOf(client.getTarget().getY()));
      extraPayload.put(PAYLOAD_SEARCH_RADIUS, String.valueOf((int) Math.round(searchRadius)));
      extraPayload.put(PAYLOAD_SCOPE, SCOPE_RQS_SEEDED);

      String requestId =
          clientNode.requestRide(
              client.getPosition().toString(),
              client.getTarget().toString(),
              effectiveFilter,
              requestForwardHops,
              "",
              extraPayload);
      requestIdByClientName.put(client.getName(), requestId);
      pendingByRequestId.put(
          requestId,
          new PendingRequest(requestId, client.getName(), stepCounter));
      log.info(
          "[P2P-COLLECTOR] published requestId={} client={} scope=rqs-seeded searchRadius={} seededVehicles={} forwardHops={}",
          requestId,
          client.getName(),
          Math.round(searchRadius),
          rqsVehicleNodeIds.size(),
          requestForwardHops);
      refreshStatus(EVENT_REQUEST_PUBLISHED_PREFIX + requestId);
    }
  }

  private void retriggerRequestsIfNeeded(Collection<Client> waitingClients) {
    int republishTicks = Math.max(1, parameters.getOrDefault(P2P_REQUEST_REPUBLISH_TICKS, 3));
    for (Client client : waitingClients) {
      String requestId = requestIdByClientName.get(client.getName());
      if (requestId == null) {
        continue;
      }

      PendingRequest pending = pendingByRequestId.get(requestId);
      if (pending == null || pending.committedVehicleNodeId != null) {
        continue;
      }

      if (pending.bestOfferVehicleNodeId == null
          && stepCounter - pending.lastPublishedStep >= republishTicks) {
        log.info(
            "[P2P-COLLECTOR] republish trigger client={} requestId={} elapsedTicks={} publishCount={}",
            client.getName(),
            requestId,
            stepCounter - pending.lastPublishedStep,
            pending.publishCount);
        removePendingForClient(client.getName());
      }
    }
  }


  private void applyVehicleDispatchConfig(Map<String, Integer> config) {
    String strategy = resolveVehicleSelectionStrategy(config);
    System.setProperty(P2PSystemProperties.VEHICLE_OPEN_REQUEST_STRATEGY, strategy);
    System.setProperty(
        P2PSystemProperties.VEHICLE_COMMIT_LEASE_TICKS,
        String.valueOf(Math.max(1, config.getOrDefault(P2P_VEHICLE_COMMIT_LEASE_TICKS, 20))));
    System.setProperty(
        P2PSystemProperties.VEHICLE_REBID_MIN_INTERVAL_TICKS,
        String.valueOf(Math.max(0, config.getOrDefault(P2P_VEHICLE_REBID_INTERVAL_TICKS, 1))));
    System.setProperty(
        P2PSystemProperties.VEHICLE_REQUEST_CACHE_TTL_TICKS,
        String.valueOf(Math.max(1, config.getOrDefault(P2P_VEHICLE_REQUEST_CACHE_TTL_TICKS, 120))));
  }

  private String resolveVehicleSelectionStrategy(Map<String, Integer> config) {
    String configured = System.getProperty(P2PSystemProperties.VEHICLE_OPEN_REQUEST_STRATEGY, "");
    if (configured != null && !configured.isBlank()) {
      return configured.trim();
    }

    int code = config.getOrDefault(P2P_VEHICLE_OPEN_CLIENT_STRATEGY, VEHICLE_STRATEGY_CODE_NEAREST);
    if (code == VEHICLE_STRATEGY_CODE_GREEDY) {
      return GreedyVehicleRequestSelectionStrategy.KEY;
    }
    return NearestVehicleRequestSelectionStrategy.KEY;
  }

  private double resolveSearchRadius() {
    return Math.max(1, parameters.getOrDefault(P2P_FIXED_SEARCH_RADIUS, 5000));
  }

  private Set<String> resolveRqsVehicleNodeIds(World world, Client client, double searchRadius) {
    if (world == null || isEmbeddedSimulationMode(parameters) && taxiNameToVehicleNodeId.isEmpty()) {
      return Set.of();
    }

    Set<Taxi> taxisInRange =
        rangeQuerySystem.findTaxisInRange(world, client.getPosition(), client.getTarget(), searchRadius);
    if (taxisInRange.isEmpty()) {
      return Set.of();
    }

    Set<String> nodeIds = new HashSet<>();
    for (Taxi taxi : taxisInRange) {
      String nodeId = taxiNameToVehicleNodeId.get(taxi.getName());
      if (nodeId != null && !nodeId.isBlank()) {
        nodeIds.add(nodeId);
      }
    }
    return nodeIds;
  }


  private void handleIncoming(List<P2PMessage> messages) {
    for (P2PMessage message : messages) {
      if (P2PTopics.TOPOLOGY_SCAN_RESPONSE.equals(message.topic())) {
        handleTopologyScanResponse(message);
        continue;
      }
      if (P2PTopics.TOPOLOGY_SCAN_REQUEST.equals(message.topic())) {
        continue;
      }

      PendingRequest pending = pendingByRequestId.get(message.requestId());
      if (pending == null) {
        continue;
      }

      if (P2PTopics.RIDE_OFFER.equals(message.topic())) {
        handleOffer(message, pending);
      } else if (P2PTopics.RIDE_COMMIT.equals(message.topic())) {
        handleCommit(message, pending);
      }
    }
  }

  private void handleTopologyScanResponse(P2PMessage message) {
    Map<String, String> payload = KeyValuePayload.parse(message.payload());
    String nodeId = payload.getOrDefault(PAYLOAD_NODE, message.senderId());
    String role = payload.getOrDefault(PAYLOAD_ROLE, UNKNOWN_ROLE);
    Set<String> neighbors = parseNeighbors(payload.get(PAYLOAD_NEIGHBORS));
    Set<String> shortcutNeighbors = parseNeighbors(payload.get(PAYLOAD_SHORTCUT_NEIGHBORS));

    topologyViewsByNodeId.put(
        nodeId,
        new TopologyPeerView(nodeId, role, neighbors, shortcutNeighbors));
    refreshStatus(EVENT_TOPOLOGY_RESPONSE_PREFIX + nodeId);
  }

  private void handleOffer(P2PMessage message, PendingRequest pending) {
    if (pending.committedVehicleNodeId != null) {
      return;
    }

    Map<String, String> payload = KeyValuePayload.parse(message.payload());
    String vehicleNodeId = payload.getOrDefault(PAYLOAD_VEHICLE, message.senderId());
    registerTaxiKnowledge(vehicleNodeId, pending.clientName);
    int etaSeconds = parseEtaSeconds(payload.get(PAYLOAD_ETA_SECONDS));

    if (pending.firstOfferAtStep == 0) {
      pending.firstOfferAtStep = stepCounter;
    }
    if (!isBetterOffer(vehicleNodeId, etaSeconds, pending)) {
      return;
    }

    pending.bestOfferVehicleNodeId = vehicleNodeId;
    pending.bestOfferEtaSeconds = etaSeconds;
    log.info(
        "[P2P-COLLECTOR] tracked best offer requestId={} client={} vehicle={} etaSeconds={}",
        pending.requestId,
        pending.clientName,
        vehicleNodeId,
        etaSeconds);
    refreshStatus(EVENT_OFFER_TRACKED_PREFIX + pending.requestId);
  }

  private void handleCommit(P2PMessage message, PendingRequest pending) {
    Map<String, String> payload = KeyValuePayload.parse(message.payload());
    String vehicleNodeId = payload.getOrDefault(PAYLOAD_VEHICLE, message.senderId());
    if (pending.committedVehicleNodeId != null && !pending.committedVehicleNodeId.equals(vehicleNodeId)) {
      log.info(
          "[P2P-COLLECTOR] ignored additional commit requestId={} committedBy={} alreadyCommittedBy={}",
          pending.requestId,
          vehicleNodeId,
          pending.committedVehicleNodeId);
      return;
    }
    pending.committedVehicleNodeId = vehicleNodeId;
    log.info(
        "[P2P-COLLECTOR] commit requestId={} client={} vehicle={}",
        pending.requestId,
        pending.clientName,
        vehicleNodeId);
    refreshStatus(EVENT_COMMIT_PREFIX + pending.requestId);
  }

  private Taxi selectTaxiForCommit(World world, String vehicleNodeId) {
    Set<Taxi> taxiCandidates = getEmptyTaxis(world);
    if (taxiCandidates.isEmpty()) {
      return null;
    }
    String mappedTaxiName = vehicleNodeToTaxiName.getOrDefault(vehicleNodeId, vehicleNodeId);
    Optional<Taxi> committedTaxi =
        taxiCandidates.stream().filter(taxi -> taxi.getName().equals(mappedTaxiName)).findFirst();
    return committedTaxi.orElse(null);
  }


  private long resolveTopologyScanTicks() {
    return Math.max(1, parameters.getOrDefault(P2P_TOPOLOGY_SCAN_TICKS, 20));
  }

  private boolean isBetterOffer(String vehicleNodeId, int etaSeconds, PendingRequest pending) {
    // "Best offer" currently means lowest announced etaSeconds, not geometric distance proximity.
    if (pending.bestOfferVehicleNodeId == null) {
      return true;
    }
    if (etaSeconds < pending.bestOfferEtaSeconds) {
      return true;
    }
    return etaSeconds == pending.bestOfferEtaSeconds
        && vehicleNodeId.compareTo(pending.bestOfferVehicleNodeId) < 0;
  }

  private int parseEtaSeconds(String value) {
    if (value == null || value.isBlank()) {
      return Integer.MAX_VALUE;
    }
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException ex) {
      return Integer.MAX_VALUE;
    }
  }

  private void cleanupNoLongerWaiting(Collection<Client> waitingClients) {
    Set<String> waitingNames = new HashSet<>();
    for (Client client : waitingClients) {
      waitingNames.add(client.getName());
    }

    List<String> staleClients = new ArrayList<>();
    for (String clientName : requestIdByClientName.keySet()) {
      if (!waitingNames.contains(clientName)) {
        staleClients.add(clientName);
      }
    }
    for (String staleClient : staleClients) {
      removePendingForClient(staleClient);
    }
  }

  private void removePendingForClient(String clientName) {
    String requestId = requestIdByClientName.remove(clientName);
    if (requestId != null) {
      pendingByRequestId.remove(requestId);
    }
    removeKnowledgeForClient(clientName);
  }

  private void removeKnowledgeForClient(String clientName) {
    if (clientName == null || clientName.isBlank()) {
      return;
    }
    taxiKnowledgeByClientIds.values().forEach(clientIds -> clientIds.remove(clientName));
    taxiKnowledgeByClientIds.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue().isEmpty());
  }

  private void registerTaxiKnowledge(String vehicleNodeId, String clientName) {
    if (clientName == null || clientName.isBlank() || vehicleNodeId == null || vehicleNodeId.isBlank()) {
      return;
    }
    String taxiDisplayId = vehicleNodeToTaxiName.getOrDefault(vehicleNodeId, vehicleNodeId);
    taxiKnowledgeByClientIds
        .computeIfAbsent(taxiDisplayId, ignored -> ConcurrentHashMap.newKeySet())
        .add(clientName);
  }

  @Override
  public Map<String, String> getP2PStatus() {
    return new LinkedHashMap<>(p2pStatus);
  }

  @Override
  public Map<String, Set<String>> getTaxiKnowledgeByClientIds() {
    Set<String> activeClientNames =
        pendingByRequestId.values().stream().map(pending -> pending.clientName).collect(Collectors.toSet());

    if (isEmbeddedSimulationMode() && !localVehicleNodesByTaxiName.isEmpty()) {
      Map<String, Set<String>> liveSnapshot = new LinkedHashMap<>();
      localVehicleNodesByTaxiName.forEach(
          (taxiName, vehicleNode) -> {
            Set<String> knownClientIds = vehicleNode.knownClientIdsSnapshot();
            if (knownClientIds != null && !knownClientIds.isEmpty()) {
              Set<String> activeKnownClientIds =
                  knownClientIds.stream().filter(activeClientNames::contains).collect(Collectors.toSet());
              if (!activeKnownClientIds.isEmpty()) {
                liveSnapshot.put(taxiName, Set.copyOf(activeKnownClientIds));
              }
            }
          });
      return liveSnapshot;
    }

    Map<String, Set<String>> snapshot = new LinkedHashMap<>();
    taxiKnowledgeByClientIds.forEach(
        (taxiId, clientIds) -> {
          Set<String> activeKnownClientIds =
              clientIds.stream().filter(activeClientNames::contains).collect(Collectors.toSet());
          if (!activeKnownClientIds.isEmpty()) {
            snapshot.put(taxiId, Set.copyOf(activeKnownClientIds));
          }
        });
    return snapshot;
  }

  @Override
  public boolean requestP2PTopologyScan() {
    if (clientNode == null) {
      return false;
    }
    requestTopologyScanIfDue(true);
    refreshStatus(EVENT_TOPOLOGY_SCAN_REQUESTED_MANUAL);
    return true;
  }

  @Override
  public P2PNetworkSnapshot getP2PNetworkSnapshot() {
    if (clientNode == null || network == null) {
      return P2PNetworkSnapshot.empty();
    }

    requestTopologyScanIfDue(false);
    upsertLocalTopologyView();

    String localNodeId = clientNode.descriptor().id();
    Map<String, NodeDescriptor> peerById =
        network.peers().stream().collect(java.util.stream.Collectors.toMap(NodeDescriptor::id, peer -> peer));

    Set<String> nodeIds = new HashSet<>(peerById.keySet());
    nodeIds.add(localNodeId);
    nodeIds.addAll(topologyViewsByNodeId.keySet());
    topologyViewsByNodeId.values().forEach(view -> nodeIds.addAll(view.neighborIds));

    List<String> orderedNodeIds = nodeIds.stream().sorted().toList();
    List<P2PNetworkNodeSnapshot> nodes = new ArrayList<>(orderedNodeIds.size());
    for (String nodeId : orderedNodeIds) {
      boolean localNode = localNodeId.equals(nodeId);
      String role = resolveRole(nodeId, peerById, localNode);
      nodes.add(new P2PNetworkNodeSnapshot(nodeId, role, localNode));
    }

    Map<String, Boolean> shortcutByEdgeKey = new HashMap<>();
    for (TopologyPeerView view : topologyViewsByNodeId.values()) {
      for (String neighborId : view.neighborIds) {
        String key = edgeKey(view.nodeId, neighborId);
        if (!key.isBlank()) {
          boolean shortcut = view.shortcutNeighborIds.contains(neighborId);
          shortcutByEdgeKey.merge(key, shortcut, (existing, candidate) -> existing || candidate);
        }
      }
    }

    List<P2PNetworkEdgeSnapshot> edges =
        shortcutByEdgeKey.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(
                entry -> {
                  String edgeKey = entry.getKey();
                  int separator = edgeKey.indexOf(EDGE_KEY_SEPARATOR);
                  if (separator < 1 || separator >= edgeKey.length() - 1) {
                    return null;
                  }
                  String from = edgeKey.substring(0, separator);
                  String to = edgeKey.substring(separator + 1);
                  return new P2PNetworkEdgeSnapshot(from, to, entry.getValue());
                })
            .filter(java.util.Objects::nonNull)
            .toList();

    return new P2PNetworkSnapshot(localNodeId, nodes, edges);
  }

  private void requestTopologyScanIfDue(boolean force) {
    if (clientNode == null) {
      return;
    }

    if (!force && stepCounter - lastTopologyScanAtStep < resolveTopologyScanTicks()) {
      return;
    }

    lastTopologyScanAtStep = stepCounter;
    lastTopologyScanId = clientNode.requestTopologyScan();
    upsertLocalTopologyView();
  }

  private void upsertLocalTopologyView() {
    if (clientNode == null || network == null) {
      return;
    }

    String localNodeId = clientNode.descriptor().id();
    Set<String> neighbors = clientNode.overlayNeighborIdsSnapshot();
    topologyViewsByNodeId.put(
        localNodeId,
        new TopologyPeerView(
            localNodeId,
            clientNode.descriptor().role().name(),
            neighbors,
            Set.of()));
  }

  private Set<String> parseNeighbors(String value) {
    if (value == null || value.isBlank()) {
      return Set.of();
    }

    Set<String> neighbors = new HashSet<>();
    for (String part : value.split(",")) {
      String id = part.trim();
      if (!id.isBlank()) {
        neighbors.add(id);
      }
    }
    return neighbors;
  }

  private String resolveRole(String nodeId, Map<String, NodeDescriptor> peerById, boolean localNode) {
    if (localNode && clientNode != null) {
      return clientNode.descriptor().role().name();
    }

    NodeDescriptor peer = peerById.get(nodeId);
    if (peer != null) {
      return peer.role().name();
    }

    TopologyPeerView view = topologyViewsByNodeId.get(nodeId);
    if (view != null && view.role != null && !view.role.isBlank()) {
      return view.role;
    }
    return UNKNOWN_ROLE;
  }


  private String edgeKey(String leftNodeId, String rightNodeId) {
    if (leftNodeId == null || rightNodeId == null || leftNodeId.equals(rightNodeId)) {
      return "";
    }
    return leftNodeId.compareTo(rightNodeId) < 0
        ? leftNodeId + EDGE_KEY_SEPARATOR + rightNodeId
        : rightNodeId + EDGE_KEY_SEPARATOR + leftNodeId;
  }

  @Override
  public void shutdown() {
    stopNetworkNode();
    pendingByRequestId.clear();
    requestIdByClientName.clear();
    taxiKnowledgeByClientIds.clear();
    vehicleNodeToTaxiName.clear();
    taxiNameToVehicleNodeId.clear();
    processedInboxMessages = 0;
    stepCounter = 0;
    commitRoundRobinCursor = 0;
    topologyViewsByNodeId.clear();
    lastTopologyScanAtStep = Long.MIN_VALUE;
    lastTopologyScanId = "";
  }

  private void ensureCollectorNode(Map<String, Integer> config) {
    applyOverlayConfig(config);
    if (clientNode != null && network != null) {
      if (!collectorNodeId.isBlank()) {
        System.setProperty(P2PSystemProperties.OVERLAY_COLLECTOR_NODE_ID, collectorNodeId);
      }
      return;
    }

    if (isEmbeddedSimulationMode(config)) {
      String nodeId = NODE_ID_COLLECTOR_LOCAL;
      collectorNodeId = nodeId;
      System.setProperty(P2PSystemProperties.OVERLAY_COLLECTOR_NODE_ID, nodeId);
      network = new InMemoryP2PNetwork();
      clientNode = new ClientP2PService(nodeId, network);
      clientNode.start();
      log.info("P2P collector node started in LOCAL simulation mode: id={}", nodeId);
      return;
    }

    int tcpPort = config.getOrDefault(P2P_TCP_PORT, 46100);
    int discoveryPort = config.getOrDefault(P2P_DISCOVERY_PORT, 45892);
    String multicastGroup = resolveMulticastGroup(config);
    String nodeId = NODE_ID_COLLECTOR_PREFIX + tcpPort;
    collectorNodeId = nodeId;
    System.setProperty(P2PSystemProperties.OVERLAY_COLLECTOR_NODE_ID, nodeId);

    network = new LanP2PNetwork(multicastGroup, discoveryPort, tcpPort, 10_000, 2_000);
    clientNode = new ClientP2PService(nodeId, network);
    clientNode.start();

    log.info(
        "P2P collector node started: id={}, tcpPort={}, discoveryPort={}, multicastGroup={}",
        nodeId,
        tcpPort,
        discoveryPort,
        multicastGroup);
  }

  private void ensureLocalVehicleNodes(World world) {
    if (!isEmbeddedSimulationMode() || network == null) {
      return;
    }

    Set<String> activeTaxiNames = new HashSet<>();
    for (Taxi taxi : world.getTaxis().stream().sorted(Comparator.comparing(Taxi::getName)).toList()) {
      activeTaxiNames.add(taxi.getName());
      VehicleP2PService existing = localVehicleNodesByTaxiName.get(taxi.getName());
      if (existing != null) {
        continue;
      }

      String vehicleNodeId = NODE_ID_VEHICLE_PREFIX + taxi.getName();
      VehicleP2PService vehicleNode = new VehicleP2PService(vehicleNodeId, network);
      vehicleNode.start();
      localVehicleNodesByTaxiName.put(taxi.getName(), vehicleNode);
      vehicleNodeToTaxiName.put(vehicleNodeId, taxi.getName());
      taxiNameToVehicleNodeId.put(taxi.getName(), vehicleNodeId);
      log.info("[P2P-COLLECTOR] created local vehicle node taxi={} nodeId={}", taxi.getName(), vehicleNodeId);
    }

    List<String> stale =
        localVehicleNodesByTaxiName.keySet().stream()
            .filter(taxiName -> !activeTaxiNames.contains(taxiName))
            .toList();
    for (String taxiName : stale) {
      VehicleP2PService node = localVehicleNodesByTaxiName.remove(taxiName);
      if (node != null) {
        vehicleNodeToTaxiName.remove(node.descriptor().id());
        taxiNameToVehicleNodeId.remove(taxiName);
        node.stop();
      }
    }
  }

  private void syncLocalVehicleStates(World world) {
    if (!isEmbeddedSimulationMode()) {
      return;
    }

    for (Taxi taxi : world.getTaxis()) {
      VehicleP2PService node = localVehicleNodesByTaxiName.get(taxi.getName());
      if (node != null) {
        node.setSimulationState(
            taxi.isEmpty(), taxi.getPosition().getX(), taxi.getPosition().getY(), stepCounter);
      }
    }
  }

  private void applyOverlayConfig(Map<String, Integer> config) {
    int maxNeighbors = Math.max(1, config.getOrDefault(P2P_OVERLAY_MAX_NEIGHBORS, 3));
    int shortcuts = Math.max(0, config.getOrDefault(P2P_OVERLAY_SHORTCUTS, 1));

    System.setProperty(P2PSystemProperties.OVERLAY_MAX_NEIGHBORS, String.valueOf(maxNeighbors));
    System.setProperty(P2PSystemProperties.OVERLAY_SHORTCUTS, String.valueOf(shortcuts));
  }

  private boolean isEmbeddedSimulationMode() {
    return isEmbeddedSimulationMode(parameters);
  }

  private boolean isEmbeddedSimulationMode(Map<String, Integer> config) {
    return config.getOrDefault(EMBEDDED_MODE_PROPERTY, 1) == 1;
  }

  private String resolveMulticastGroup(Map<String, Integer> config) {
    int a = octet(config, P2P_MULTICAST_A, 239);
    int b = octet(config, P2P_MULTICAST_B, 255);
    int c = octet(config, P2P_MULTICAST_C, 42);
    int d = octet(config, P2P_MULTICAST_D, 99);
    return a + "." + b + "." + c + "." + d;
  }

  private int octet(Map<String, Integer> config, String key, int defaultValue) {
    int value = config.getOrDefault(key, defaultValue);
    return Math.max(0, Math.min(255, value));
  }

  private void deriveTaxiCountFromNetwork(Map<String, Integer> prepared) {
    if (network == null) {
      return;
    }

    int waitMs = prepared.getOrDefault(P2P_DISCOVERY_WAIT_MS, 6000);
    int settleMs = 1500;
    long deadline = System.currentTimeMillis() + Math.max(0, waitMs);

    List<NodeDescriptor> bestVehicles = List.of();
    long lastChangeAt = System.currentTimeMillis();
    long lastCount = -1;

    while (System.currentTimeMillis() < deadline) {
      List<NodeDescriptor> currentVehicles =
          network.peers().stream()
              .filter(peer -> peer.role() == NodeRole.VEHICLE)
              .sorted(java.util.Comparator.comparing(NodeDescriptor::id))
              .toList();

      if (currentVehicles.size() != lastCount) {
        lastCount = currentVehicles.size();
        lastChangeAt = System.currentTimeMillis();
      }
      if (currentVehicles.size() > bestVehicles.size()) {
        bestVehicles = currentVehicles;
      }
      if (!bestVehicles.isEmpty() && System.currentTimeMillis() - lastChangeAt >= settleMs) {
        break;
      }

      try {
        Thread.sleep(250);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }

    List<NodeDescriptor> vehicles = bestVehicles;

    vehicleNodeToTaxiName.clear();
    taxiNameToVehicleNodeId.clear();
    for (int i = 0; i < vehicles.size(); i++) {
      String taxiName = TAXI_NAME_PREFIX + i;
      vehicleNodeToTaxiName.put(vehicles.get(i).id(), taxiName);
      taxiNameToVehicleNodeId.put(taxiName, vehicles.get(i).id());
    }

    prepared.put(KEY_TAXI_COUNT, vehicles.size());
    log.info(
        "[P2P-COLLECTOR] derived taxiCount={} from discovered vehicle peers={}",
        vehicles.size(),
        vehicles.stream().map(NodeDescriptor::id).toList());
    refreshStatus(EVENT_DERIVED_TAXI_COUNT_PREFIX + vehicles.size());
  }

  private long countVehiclePeers(Set<NodeDescriptor> peers) {
    return peers.stream().filter(peer -> peer.role() == NodeRole.VEHICLE).count();
  }

  private void refreshStatus(String event) {
    p2pStatus.put(STATUS_MODE, VALUE_P2P);
    p2pStatus.put(STATUS_COLLECTOR_NODE, clientNode != null ? clientNode.descriptor().id() : VALUE_UNKNOWN);
    p2pStatus.put(STATUS_KNOWN_PEERS, String.valueOf(network != null ? network.peers().size() : 0));
    p2pStatus.put(
        STATUS_VEHICLE_PEERS,
        String.valueOf(network != null ? countVehiclePeers(network.peers()) : 0));
    p2pStatus.put(STATUS_PENDING_REQUESTS, String.valueOf(pendingByRequestId.size()));
    p2pStatus.put(STATUS_MAPPED_VEHICLES, String.valueOf(vehicleNodeToTaxiName.size()));
    p2pStatus.put(STATUS_TOPOLOGY_VIEWS, String.valueOf(topologyViewsByNodeId.size()));
    p2pStatus.put(
        STATUS_TOPOLOGY_SCAN_ID,
        lastTopologyScanId == null || lastTopologyScanId.isBlank() ? VALUE_UNKNOWN : lastTopologyScanId);
    p2pStatus.put(STATUS_OVERLAY_MODE, VALUE_SMALL_WORLD);
    p2pStatus.put(
        STATUS_OVERLAY_MAX_NEIGHBORS,
        System.getProperty(P2PSystemProperties.OVERLAY_MAX_NEIGHBORS, DEFAULT_OVERLAY_MAX_NEIGHBORS));
    p2pStatus.put(
        STATUS_OVERLAY_SHORTCUTS,
        System.getProperty(P2PSystemProperties.OVERLAY_SHORTCUTS, DEFAULT_OVERLAY_SHORTCUTS));
    String fixedRadius = String.valueOf(parameters.getOrDefault(P2P_FIXED_SEARCH_RADIUS, 5000));
    p2pStatus.put(STATUS_RQS_FIXED_RADIUS, fixedRadius);
    p2pStatus.put(STATUS_RQS_CENTER, VALUE_CLIENT);
    p2pStatus.put(
        STATUS_RQS_RECOGNITION_MODE,
        parameters.getOrDefault(P2P_RQS_ROUTE_PROXIMITY_MODE, 0) == 0
            ? VALUE_TAXI_POSITION
            : VALUE_TAXI_ROUTE);
    p2pStatus.put(
        STATUS_VEHICLE_CLIENT_SELECTION,
        System.getProperty(
            P2PSystemProperties.VEHICLE_OPEN_REQUEST_STRATEGY,
            NearestVehicleRequestSelectionStrategy.KEY));
    p2pStatus.put(STATUS_CLIENT_RANGE_FILTER, VALUE_SEED_ONLY);
    p2pStatus.put(STATUS_RUNTIME_MODE, isEmbeddedSimulationMode() ? VALUE_EMBEDDED : VALUE_LAN);
    p2pStatus.put(STATUS_LOCAL_VEHICLE_NODES, String.valueOf(localVehicleNodesByTaxiName.size()));
    p2pStatus.put(STATUS_LAST_EVENT, event);
  }

  private void stopNetworkNode() {
    for (VehicleP2PService vehicleNode : localVehicleNodesByTaxiName.values()) {
      vehicleNode.stop();
    }
    localVehicleNodesByTaxiName.clear();
    taxiNameToVehicleNodeId.clear();
    if (clientNode != null) {
      clientNode.stop();
      clientNode = null;
    }
    network = null;
    if (!collectorNodeId.isBlank()) {
      String configuredCollector = System.getProperty(P2PSystemProperties.OVERLAY_COLLECTOR_NODE_ID, "");
      if (collectorNodeId.equals(configuredCollector)) {
        System.clearProperty(P2PSystemProperties.OVERLAY_COLLECTOR_NODE_ID);
      }
      collectorNodeId = "";
    }
    topologyViewsByNodeId.clear();
    lastTopologyScanAtStep = Long.MIN_VALUE;
    lastTopologyScanId = "";
    p2pStatus.clear();
    taxiKnowledgeByClientIds.clear();
  }

  private static class PendingRequest {
    private final String requestId;
    private final String clientName;
    private final long lastPublishedStep;
    private final int publishCount;
    private long firstOfferAtStep;
    private String bestOfferVehicleNodeId;
    private int bestOfferEtaSeconds = Integer.MAX_VALUE;
    private String committedVehicleNodeId;

    private PendingRequest(String requestId, String clientName, long lastPublishedStep) {
      this.requestId = requestId;
      this.clientName = clientName;
      this.lastPublishedStep = lastPublishedStep;
      this.publishCount = 1;
      this.firstOfferAtStep = 0L;
    }
  }

  private static class TopologyPeerView {
    private final String nodeId;
    private final String role;
    private final Set<String> neighborIds;
    private final Set<String> shortcutNeighborIds;

    private TopologyPeerView(
        String nodeId,
        String role,
        Set<String> neighborIds,
        Set<String> shortcutNeighborIds) {
      this.nodeId = nodeId;
      this.role = role;
      this.neighborIds = neighborIds == null ? Set.of() : Set.copyOf(neighborIds);
      this.shortcutNeighborIds =
          shortcutNeighborIds == null
              ? Set.of()
              : shortcutNeighborIds.stream().filter(this.neighborIds::contains).collect(Collectors.toSet());
    }
  }
}

