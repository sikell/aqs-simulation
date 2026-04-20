package de.sikeller.aqs.taxi.algorithm.collector;

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
import de.sikeller.aqs.p2p.api.P2PPayloadKeys;
import de.sikeller.aqs.p2p.api.P2PSystemProperties;
import de.sikeller.aqs.p2p.api.P2PTopics;
import de.sikeller.aqs.p2p.service.ClientP2PService;
import de.sikeller.aqs.p2p.service.KeyValuePayload;
import de.sikeller.aqs.p2p.service.VehicleP2PService;
import de.sikeller.aqs.p2p.service.strategy.NearestVehicleRequestSelectionStrategy;
import de.sikeller.aqs.p2p.transport.inmemory.InMemoryP2PNetwork;
import de.sikeller.aqs.p2p.transport.network.LanP2PNetwork;
import de.sikeller.aqs.taxi.algorithm.AbstractTaxiAlgorithm;
import de.sikeller.aqs.taxi.algorithm.distributed.rqs.RangeQuerySystem;
import de.sikeller.aqs.taxi.algorithm.distributed.rqs.SimulatedRangeQuerySystem;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * P2P-Collector - dezentraler Algorithmus
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
    applyVehicleDispatchConfig();
  }

  private P2PNetwork network;
  private ClientP2PService clientNode;
  private final Map<String, VehicleP2PService> localVehicleNodesByTaxiName = new HashMap<>();
  private long stepCounter = 0;
  private final TaxiCollectorRuntimeState runtimeState = new TaxiCollectorRuntimeState();
  private final Map<String, String> vehicleNodeToTaxiName = new HashMap<>();
  private final Map<String, String> taxiNameToVehicleNodeId = new HashMap<>();
  private final Map<String, TopologyPeerView> topologyViewsByNodeId = new ConcurrentHashMap<>();
  private final RangeQuerySystem rangeQuerySystem = new SimulatedRangeQuerySystem();
  private long lastTopologyScanAtStep = Long.MIN_VALUE;
  private String lastTopologyScanId = "";
  private String lastStatusEvent = VALUE_UNKNOWN;

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
  private static final String P2P_OVERLAY_MIN_NEIGHBORS = "p2pOverlayMinNeighbors";
  private static final String P2P_OVERLAY_MAX_NEIGHBORS = "p2pOverlayMaxNeighbors";
  private static final String P2P_OVERLAY_SHORTCUTS = "p2pOverlayShortcuts";
  private static final String P2P_OVERLAY_POSITION_TTL_TICKS = "p2pOverlayPositionTtlTicks";
  private static final String UNKNOWN_ROLE = "UNKNOWN";
  private static final String EMBEDDED_MODE_PROPERTY = "p2pEmbeddedSimulation";
  private static final String STATUS_MODE = "mode";
  private static final String STATUS_COLLECTOR_NODE = "collectorNode";
  private static final String STATUS_KNOWN_PEERS = "knownPeers";
  private static final String STATUS_VEHICLE_PEERS = "vehiclePeers";
  private static final String STATUS_PENDING_REQUESTS = "pendingRequests";
  private static final String STATUS_MAPPED_VEHICLES = "mappedVehicles";
  private static final String STATUS_TOPOLOGY_VIEWS = "topologyViews";
  private static final String STATUS_TOPOLOGY_SCAN_ID = "topologyScanId";
  private static final String STATUS_OVERLAY_MODE = "overlayMode";
  private static final String STATUS_OVERLAY_MIN_NEIGHBORS = "overlayMinNeighbors";
  private static final String STATUS_OVERLAY_MAX_NEIGHBORS = "overlayMaxNeighbors";
  private static final String STATUS_OVERLAY_MAX_DISTANCE = "overlayMaxDistance";
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
  private static final String VALUE_EMBEDDED = "EMBEDDED";
  private static final String VALUE_LAN = "LAN";
  private static final String DEFAULT_OVERLAY_MIN_NEIGHBORS = "1";
  private static final String DEFAULT_OVERLAY_SHORTCUTS = "1";
  private static final String DEFAULT_OVERLAY_POSITION_TTL_TICKS = "200";
  private static final int DEFAULT_OVERLAY_MAX_DISTANCE_FACTOR = 2;
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
  private static final int DEFAULT_VEHICLE_COMMIT_LEASE_TICKS = 20;
  private static final int DEFAULT_VEHICLE_REOFFER_INTERVAL_TICKS = 1;
  private static final int DEFAULT_VEHICLE_REQUEST_CACHE_TTL_TICKS = 120;
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
        new AlgorithmParameter(P2P_TOPOLOGY_SCAN_TICKS, 20),
        new AlgorithmParameter(P2P_REQUEST_FORWARD_HOPS, 2),
        new AlgorithmParameter(P2P_REQUEST_REPUBLISH_TICKS, 3),
        new AlgorithmParameter(P2P_FIXED_SEARCH_RADIUS, 5000),
        new AlgorithmParameter(EMBEDDED_MODE_PROPERTY, 1),
        new AlgorithmParameter(P2P_OVERLAY_MIN_NEIGHBORS, 1),
        new AlgorithmParameter(P2P_OVERLAY_MAX_NEIGHBORS, 100),
        new AlgorithmParameter(P2P_OVERLAY_SHORTCUTS, 1),
        new AlgorithmParameter(P2P_OVERLAY_POSITION_TTL_TICKS, 200));
  }

  @Override
  public Map<String, Integer> prepareWorldParameters(Map<String, Integer> inputParameters) {
    Map<String, Integer> prepared = new HashMap<>(inputParameters);
    setParameters(prepared);
    applyOverlayConfig(prepared);
    stopNetworkNode(); // recreate collector node for each simulation init
    ensureCollectorNode(prepared);
    if (!isEmbeddedSimulationMode(prepared)) {
      deriveTaxiCountFromNetwork(prepared);
    }
    return prepared;
  }

  @Override
  public void init(World world) {
    stepCounter = 0;
    runtimeState.clear();
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

    processNetworkCycle(world, waitingClients);

    if (waitingClients.isEmpty()) {
      return ok();
    }

    if (applyCommittedAssignments(world, waitingClients) > 0) {
      return ok();
    }
    refreshStatus(EVENT_WAITING_FOR_COMMIT);
    return ok();
  }

  private void processNetworkCycle(World world, Collection<Client> waitingClients) {
    if (clientNode == null) {
      return;
    }

    if (isEmbeddedSimulationMode()) {
      ensureLocalVehicleNodes(world);
      syncLocalVehicleStates(world);
    }
    retriggerRequestsIfNeeded(waitingClients);
    publishNewRequests(world, waitingClients);
    processInbox(world, waitingClients);
    logPeriodicRuntimeStatus(world, waitingClients);
  }

  private void processInbox(World world, Collection<Client> waitingClients) {
    List<P2PMessage> delta = clientNode.drainInbox();
    if (delta.isEmpty()) {
      return;
    }

    int newMessages = delta.size();
    long newOffers = delta.stream().filter(m -> P2PTopics.RIDE_OFFER.equals(m.topic())).count();
    handleIncoming(delta);
    log.info(
        "[P2P-COLLECTOR] event t={} peers={} waiting={} newMessages={} newOffers={} inbox={}",
        world.getCurrentTime(),
        network.peers().size(),
        waitingClients.size(),
        newMessages,
        newOffers,
        newMessages);
  }

  private void logPeriodicRuntimeStatus(World world, Collection<Client> waitingClients) {
    if (stepCounter % 20 != 0) {
      return;
    }
    log.info(
        "[P2P-COLLECTOR] status t={} waiting={} {}",
        world.getCurrentTime(),
        waitingClients.size(),
        clientNode.runtimeStatus().asLogLine());
  }

  private int applyCommittedAssignments(World world, Collection<Client> waitingClients) {
    Map<String, Taxi> emptyTaxisByName = getEmptyTaxis(world).stream()
        .collect(Collectors.toMap(Taxi::getName, t -> t, (a, b) -> a, HashMap::new));

    int applied = 0;
    List<String> assignedClients = new ArrayList<>();
    for (Client client : waitingClients) {
      TaxiCollectorRuntimeState.PendingRequest pending = runtimeState.pendingForClient(client.getName());
      if (pending == null || !pending.isCommitted() || pending.committedVehicleNodeId() == null) {
        continue;
      }
      String mappedTaxiName = vehicleNodeToTaxiName.getOrDefault(pending.committedVehicleNodeId(), pending.committedVehicleNodeId());
      Taxi selectedTaxi = emptyTaxisByName.remove(mappedTaxiName);
      if (selectedTaxi == null) {
        continue;
      }

      world.mutate().planClientForTaxi(selectedTaxi, client, TargetList.sequentialOrders);
      log.info(
          "[P2P-COLLECTOR] assigned committed requestId={} client={} vehicle={}",
          pending.requestId(),
          client.getName(),
          pending.committedVehicleNodeId());
      assignedClients.add(client.getName());
      refreshStatus(EVENT_ASSIGNED_PREFIX + pending.requestId());
      applied++;
    }
    assignedClients.forEach(runtimeState::removePendingForClient);
    return applied;
  }

  private void publishNewRequests(World world, Collection<Client> waitingClients) {
    for (Client client : waitingClients) {
      if (runtimeState.pendingForClient(client.getName()) != null) {
        continue;
      }

      double searchRadius = resolveSearchRadius();
      Set<String> seedVehicleNodeIds = resolveInitialSeedVehicleNodeIds(world, client, searchRadius);
      if (seedVehicleNodeIds.isEmpty()) {
        log.info(
            "[P2P-COLLECTOR] skipped publish client={} reason=no-seed-vehicles searchRadius={}",
            client.getName(),
            Math.round(searchRadius));
        continue;
      }
      seedVehicleNodeIds.forEach(vehicleNodeId -> registerTaxiKnowledge(vehicleNodeId, client.getName()));
      Predicate<NodeDescriptor> effectiveFilter =
          node -> node.role() == NodeRole.VEHICLE && seedVehicleNodeIds.contains(node.id());
      int requestForwardHops = Math.max(0, parameters.getOrDefault(P2P_REQUEST_FORWARD_HOPS, 2));

      Map<String, String> extraPayload = new LinkedHashMap<>();
      extraPayload.put(P2PPayloadKeys.CLIENT_NAME, client.getName());
      extraPayload.put(P2PPayloadKeys.REQUEST_X, String.valueOf(client.getPosition().getX()));
      extraPayload.put(P2PPayloadKeys.REQUEST_Y, String.valueOf(client.getPosition().getY()));
      extraPayload.put(P2PPayloadKeys.SEARCH_RADIUS, String.valueOf((int) Math.round(searchRadius)));

      String requestId =
          clientNode.requestRide(
              client.getPosition().toString(),
              client.getTarget().toString(),
              effectiveFilter,
              requestForwardHops,
              "",
              extraPayload);
      runtimeState.putPendingRequest(requestId, client.getName(), stepCounter);
      log.info(
          "[P2P-COLLECTOR] published requestId={} client={} scope=rqs-seeded searchRadius={} seededVehicles={} forwardHops={}",
          requestId,
          client.getName(),
          Math.round(searchRadius),
          seedVehicleNodeIds.size(),
          requestForwardHops);
      refreshStatus(EVENT_REQUEST_PUBLISHED_PREFIX + requestId);
    }
  }

  private void retriggerRequestsIfNeeded(Collection<Client> waitingClients) {
    int republishTicks = Math.max(1, parameters.getOrDefault(P2P_REQUEST_REPUBLISH_TICKS, 3));
    for (Client client : waitingClients) {
      TaxiCollectorRuntimeState.PendingRequest pending = runtimeState.pendingForClient(client.getName());
      if (pending == null || pending.isCommitted()) {
        continue;
      }

      if (pending.state() == TaxiCollectorRuntimeState.RequestState.PUBLISHED
          && stepCounter - pending.lastPublishedStep() >= republishTicks) {
        log.info(
            "[P2P-COLLECTOR] republish trigger client={} requestId={} elapsedTicks={}",
            client.getName(),
            pending.requestId(),
            stepCounter - pending.lastPublishedStep());
        runtimeState.removePendingForClient(client.getName());
      }
    }
  }


  private void applyVehicleDispatchConfig() {
    String strategy = resolveVehicleSelectionStrategy();
    System.setProperty(P2PSystemProperties.VEHICLE_OPEN_REQUEST_STRATEGY, strategy);
    System.setProperty(
        P2PSystemProperties.VEHICLE_COMMIT_LEASE_TICKS,
        String.valueOf(DEFAULT_VEHICLE_COMMIT_LEASE_TICKS));
    System.setProperty(
        P2PSystemProperties.VEHICLE_REOFFER_MIN_INTERVAL_TICKS,
        String.valueOf(DEFAULT_VEHICLE_REOFFER_INTERVAL_TICKS));
    System.setProperty(
        P2PSystemProperties.VEHICLE_REQUEST_CACHE_TTL_TICKS,
        String.valueOf(DEFAULT_VEHICLE_REQUEST_CACHE_TTL_TICKS));
  }

  private String resolveVehicleSelectionStrategy() {
    String configured = System.getProperty(P2PSystemProperties.VEHICLE_OPEN_REQUEST_STRATEGY, "");
    if (configured != null && !configured.isBlank()) {
      return configured.trim();
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
    return taxisInRange.stream()
        .map(Taxi::getName)
        .map(taxiNameToVehicleNodeId::get)
        .filter(s -> s != null && !s.isBlank())
        .collect(Collectors.toSet());
  }

  private Set<String> resolveInitialSeedVehicleNodeIds(World world, Client client, double searchRadius) {
    return resolveRqsVehicleNodeIds(world, client, searchRadius);
  }


  private void handleIncoming(List<P2PMessage> messages) {
    for (P2PMessage message : messages) {
      String topic = message.topic();
      if (P2PTopics.TOPOLOGY_SCAN_RESPONSE.equals(topic)) {
        handleTopologyScanResponse(message);
        continue;
      }
      if (P2PTopics.TOPOLOGY_SCAN_REQUEST.equals(topic)) {
        continue;
      }

      TaxiCollectorRuntimeState.PendingRequest pending = runtimeState.pendingForRequestId(message.requestId());
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
    String nodeId = message.senderId();
    String role = payload.getOrDefault(P2PPayloadKeys.ROLE, UNKNOWN_ROLE);
    Set<String> neighbors = parseNeighbors(payload.get(P2PPayloadKeys.NEIGHBORS));
    Set<String> shortcutNeighbors = parseNeighbors(payload.get(P2PPayloadKeys.SHORTCUT_NEIGHBORS));

    topologyViewsByNodeId.put(
        nodeId,
        new TopologyPeerView(nodeId, role, neighbors, shortcutNeighbors));
    refreshStatus(EVENT_TOPOLOGY_RESPONSE_PREFIX + nodeId);
  }

  private void handleOffer(P2PMessage message, TaxiCollectorRuntimeState.PendingRequest pending) {
    if (pending.isCommitted()) {
      return;
    }

    Map<String, String> payload = KeyValuePayload.parse(message.payload());
    String vehicleNodeId = payload.getOrDefault(P2PPayloadKeys.VEHICLE, message.senderId());
    registerTaxiKnowledge(vehicleNodeId, pending.clientName());
    int etaSeconds = parseEtaSeconds(payload.get(P2PPayloadKeys.ETA_SECONDS));
    log.info(
        "[P2P-COLLECTOR] observed offer requestId={} client={} vehicle={} etaSeconds={}",
        pending.requestId(),
        pending.clientName(),
        vehicleNodeId,
        etaSeconds);
  }

  private void handleCommit(P2PMessage message, TaxiCollectorRuntimeState.PendingRequest pending) {
    Map<String, String> payload = KeyValuePayload.parse(message.payload());
    String vehicleNodeId = payload.getOrDefault(P2PPayloadKeys.VEHICLE, message.senderId());
    if (pending.committedVehicleNodeId() != null && !pending.committedVehicleNodeId().equals(vehicleNodeId)) {
      log.info(
          "[P2P-COLLECTOR] ignored additional commit requestId={} committedBy={} alreadyCommittedBy={}",
          pending.requestId(),
          vehicleNodeId,
          pending.committedVehicleNodeId());
      return;
    }
    pending.markCommitted(vehicleNodeId);
    log.info(
        "[P2P-COLLECTOR] commit requestId={} client={} vehicle={}",
        pending.requestId(),
        pending.clientName(),
        vehicleNodeId);
    refreshStatus(EVENT_COMMIT_PREFIX + pending.requestId());
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
    // Build a set of active waiting names once and remove directly from runtimeState
    Set<String> waitingNames = waitingClients.stream().map(Client::getName).collect(Collectors.toSet());
    for (String clientName : runtimeState.pendingClientNamesSnapshot()) {
      if (!waitingNames.contains(clientName)) {
        runtimeState.removePendingForClient(clientName);
      }
    }
  }

  private void registerTaxiKnowledge(String vehicleNodeId, String clientName) {
    if (clientName == null || clientName.isBlank() || vehicleNodeId == null || vehicleNodeId.isBlank()) {
      return;
    }
    String taxiDisplayId = vehicleNodeToTaxiName.getOrDefault(vehicleNodeId, vehicleNodeId);
    runtimeState.registerTaxiKnowledge(taxiDisplayId, clientName);
  }

  @Override
  public Map<String, String> getP2PStatus() {
    Map<String, String> status = new LinkedHashMap<>();
    status.put(STATUS_MODE, VALUE_P2P);
    status.put(STATUS_COLLECTOR_NODE, clientNode != null ? clientNode.descriptor().id() : VALUE_UNKNOWN);
    status.put(STATUS_KNOWN_PEERS, String.valueOf(network != null ? network.peers().size() : 0));
    status.put(
        STATUS_VEHICLE_PEERS,
        String.valueOf(network != null ? countVehiclePeers(network.peers()) : 0));
    status.put(STATUS_PENDING_REQUESTS, String.valueOf(runtimeState.pendingCount()));
    status.put(STATUS_MAPPED_VEHICLES, String.valueOf(vehicleNodeToTaxiName.size()));
    status.put(STATUS_TOPOLOGY_VIEWS, String.valueOf(topologyViewsByNodeId.size()));
    status.put(
        STATUS_TOPOLOGY_SCAN_ID,
        lastTopologyScanId == null || lastTopologyScanId.isBlank() ? VALUE_UNKNOWN : lastTopologyScanId);
    status.put(STATUS_OVERLAY_MODE, VALUE_SMALL_WORLD);
    String configuredMinNeighbors =
        System.getProperty(P2PSystemProperties.OVERLAY_MIN_NEIGHBORS, DEFAULT_OVERLAY_MIN_NEIGHBORS);
    status.put(STATUS_OVERLAY_MIN_NEIGHBORS, configuredMinNeighbors);
    status.put(STATUS_OVERLAY_MAX_NEIGHBORS, System.getProperty(P2PSystemProperties.OVERLAY_MAX_NEIGHBORS, "100"));
    status.put(
        STATUS_OVERLAY_SHORTCUTS,
        System.getProperty(P2PSystemProperties.OVERLAY_SHORTCUTS, DEFAULT_OVERLAY_SHORTCUTS));
    status.put(
        STATUS_OVERLAY_MAX_DISTANCE,
        System.getProperty(P2PSystemProperties.OVERLAY_MAX_DISTANCE, String.valueOf(resolveOverlayMaxDistance())));
    status.put(
        "overlayPositionTtlTicks",
        System.getProperty(P2PSystemProperties.OVERLAY_POSITION_TTL_TICKS, DEFAULT_OVERLAY_POSITION_TTL_TICKS));
    String fixedRadius = String.valueOf(parameters.getOrDefault(P2P_FIXED_SEARCH_RADIUS, 5000));
    status.put(STATUS_RQS_FIXED_RADIUS, fixedRadius);
    status.put(STATUS_RQS_CENTER, VALUE_CLIENT);
    status.put(STATUS_RQS_RECOGNITION_MODE, VALUE_TAXI_POSITION);
    status.put(
        STATUS_VEHICLE_CLIENT_SELECTION,
        System.getProperty(
            P2PSystemProperties.VEHICLE_OPEN_REQUEST_STRATEGY,
            NearestVehicleRequestSelectionStrategy.KEY));
    status.put(STATUS_CLIENT_RANGE_FILTER, "overlay-seeded-diffusion");
    status.put(STATUS_RUNTIME_MODE, isEmbeddedSimulationMode() ? VALUE_EMBEDDED : VALUE_LAN);
    status.put(STATUS_LOCAL_VEHICLE_NODES, String.valueOf(localVehicleNodesByTaxiName.size()));
    status.put(STATUS_LAST_EVENT, lastStatusEvent == null || lastStatusEvent.isBlank() ? VALUE_UNKNOWN : lastStatusEvent);
    return status;
  }

  @Override
  public Map<String, Set<String>> getTaxiKnowledgeByClientIds() {
    Set<String> activeClientNames = runtimeState.activeClientNamesSnapshot();

    if (isEmbeddedSimulationMode() && !localVehicleNodesByTaxiName.isEmpty()) {
      Map<String, Set<String>> liveSnapshot = new LinkedHashMap<>();
      localVehicleNodesByTaxiName.forEach((taxiName, vehicleNode) -> {
        Set<String> knownClientIds = vehicleNode.knownClientIdsSnapshot();
        if (knownClientIds != null && !knownClientIds.isEmpty()) {
          Set<String> activeKnownClientIds = knownClientIds.stream().filter(activeClientNames::contains).collect(Collectors.toSet());
          if (!activeKnownClientIds.isEmpty()) {
            liveSnapshot.put(taxiName, Set.copyOf(activeKnownClientIds));
          }
        }
      });
      return liveSnapshot;
    }

    return runtimeState.taxiKnowledgeSnapshot(activeClientNames);
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
    Map<String, NodeDescriptor> peerById = network.peers().stream().collect(Collectors.toMap(NodeDescriptor::id, p -> p));
    // collect node ids into a sorted set to avoid extra temporary lists
    TreeSet<String> nodeIds = new TreeSet<>(peerById.keySet());
    nodeIds.add(localNodeId);
    nodeIds.addAll(topologyViewsByNodeId.keySet());
    topologyViewsByNodeId.values().forEach(v -> nodeIds.addAll(v.neighborIds));

    List<P2PNetworkNodeSnapshot> nodes = nodeIds.stream()
        .map(nodeId -> new P2PNetworkNodeSnapshot(nodeId, resolveRole(nodeId, peerById, localNodeId.equals(nodeId)), localNodeId.equals(nodeId)))
        .collect(Collectors.toList());

    Map<String, Boolean> shortcutByEdgeKey = new HashMap<>();
    for (TopologyPeerView view : topologyViewsByNodeId.values()) {
      for (String neighborId : view.neighborIds) {
        String key = edgeKey(view.nodeId, neighborId);
        if (key.isBlank()) continue;
        boolean shortcut = view.shortcutNeighborIds.contains(neighborId);
        shortcutByEdgeKey.merge(key, shortcut, (existing, candidate) -> existing || candidate);
      }
    }

    List<P2PNetworkEdgeSnapshot> edges = shortcutByEdgeKey.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(entry -> {
          String edgeKey = entry.getKey();
          int separator = edgeKey.indexOf(EDGE_KEY_SEPARATOR);
          if (separator < 1 || separator >= edgeKey.length() - 1) return null;
          String from = edgeKey.substring(0, separator);
          String to = edgeKey.substring(separator + 1);
          return new P2PNetworkEdgeSnapshot(from, to, entry.getValue());
        })
        .filter(java.util.Objects::nonNull)
        .collect(Collectors.toList());

    return new P2PNetworkSnapshot(localNodeId, nodes, edges);
  }

  private void requestTopologyScanIfDue(boolean force) {
    if (clientNode == null) {
      return;
    }

    if (!force && stepCounter - lastTopologyScanAtStep <
            Math.max(1, parameters.getOrDefault(P2P_TOPOLOGY_SCAN_TICKS, 20))) {
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
    if (value == null || value.isBlank()) return Set.of();
    return java.util.Arrays.stream(value.split(","))
        .map(String::trim)
        .filter(s -> !s.isBlank())
        .collect(Collectors.toSet());
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
    runtimeState.clear();
    vehicleNodeToTaxiName.clear();
    taxiNameToVehicleNodeId.clear();
    stepCounter = 0;
    topologyViewsByNodeId.clear();
    lastTopologyScanAtStep = Long.MIN_VALUE;
    lastTopologyScanId = "";
    lastStatusEvent = VALUE_UNKNOWN;
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
    if (!isEmbeddedSimulationMode() || network == null) return;
    // gather active taxi names
    Set<String> activeTaxiNames = world.getTaxis().stream().map(Taxi::getName).collect(Collectors.toSet());
    // create missing local nodes
    world.getTaxis().stream()
        .sorted(Comparator.comparing(Taxi::getName))
        .forEach(taxi -> {
          if (localVehicleNodesByTaxiName.containsKey(taxi.getName())) return;
          String vehicleNodeId = NODE_ID_VEHICLE_PREFIX + taxi.getName();
          VehicleP2PService vehicleNode = new VehicleP2PService(vehicleNodeId, network);
          vehicleNode.start();
          localVehicleNodesByTaxiName.put(taxi.getName(), vehicleNode);
          vehicleNodeToTaxiName.put(vehicleNodeId, taxi.getName());
          taxiNameToVehicleNodeId.put(taxi.getName(), vehicleNodeId);
          log.info("[P2P-COLLECTOR] created local vehicle node taxi={} nodeId={}", taxi.getName(), vehicleNodeId);
        });
    // remove stale nodes using iterator to avoid extra collections
    var it = localVehicleNodesByTaxiName.keySet().iterator();
    List<String> toRemove = new ArrayList<>();
    while (it.hasNext()) {
      String taxiName = it.next();
      if (!activeTaxiNames.contains(taxiName)) toRemove.add(taxiName);
    }
    for (String taxiName : toRemove) {
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
    int minNeighbors =
        Math.max(
            1,
            config.getOrDefault(P2P_OVERLAY_MIN_NEIGHBORS, 1));
    int maxNeighbors = Math.max(1, config.getOrDefault(P2P_OVERLAY_MAX_NEIGHBORS, 100));
    int shortcuts = Math.max(0, config.getOrDefault(P2P_OVERLAY_SHORTCUTS, 1));
    long positionTtlTicks = Math.max(1, config.getOrDefault(P2P_OVERLAY_POSITION_TTL_TICKS, 200));
    long maxDistance = resolveOverlayMaxDistance(config);

    System.setProperty(P2PSystemProperties.OVERLAY_MIN_NEIGHBORS, String.valueOf(minNeighbors));
    System.setProperty(P2PSystemProperties.OVERLAY_MAX_NEIGHBORS, String.valueOf(maxNeighbors));
    System.setProperty(P2PSystemProperties.OVERLAY_SHORTCUTS, String.valueOf(shortcuts));
    System.setProperty(P2PSystemProperties.OVERLAY_POSITION_TTL_TICKS, String.valueOf(positionTtlTicks));
    System.setProperty(P2PSystemProperties.OVERLAY_MAX_DISTANCE, String.valueOf(maxDistance));
  }

  private long resolveOverlayMaxDistance() {
    return resolveOverlayMaxDistance(parameters);
  }

  private long resolveOverlayMaxDistance(Map<String, Integer> config) {
    long fixedRadius = Math.max(1, config.getOrDefault(P2P_FIXED_SEARCH_RADIUS, 5000));
    return fixedRadius * DEFAULT_OVERLAY_MAX_DISTANCE_FACTOR;
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
      List<NodeDescriptor> currentVehicles = network.peers().stream()
          .filter(peer -> peer.role() == NodeRole.VEHICLE)
          .sorted(java.util.Comparator.comparing(NodeDescriptor::id))
          .toList();

      if (currentVehicles.size() != lastCount) {
        lastCount = currentVehicles.size();
        lastChangeAt = System.currentTimeMillis();
      }
      if (currentVehicles.size() > bestVehicles.size()) bestVehicles = currentVehicles;
      if (!bestVehicles.isEmpty() && System.currentTimeMillis() - lastChangeAt >= settleMs) break;

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
    lastStatusEvent = event == null || event.isBlank() ? VALUE_UNKNOWN : event;
  }

  private void stopNetworkNode() {
    localVehicleNodesByTaxiName.values().forEach(VehicleP2PService::stop);
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
    lastStatusEvent = VALUE_UNKNOWN;
    runtimeState.clear();
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


