package de.sikeller.aqs.taxi.algorithm.collector;

import de.sikeller.aqs.model.AlgorithmParameter;
import de.sikeller.aqs.model.AlgorithmResult;
import de.sikeller.aqs.model.Client;
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
import de.sikeller.aqs.p2p.service.VehicleP2PService;
import de.sikeller.aqs.p2p.service.strategy.NearestVehicleRequestSelectionStrategy;
import de.sikeller.aqs.taxi.algorithm.AbstractTaxiAlgorithm;
import de.sikeller.aqs.taxi.algorithm.distributed.rqs.RangeQuerySystem;
import de.sikeller.aqs.taxi.algorithm.distributed.rqs.SimulatedRangeQuerySystem;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
  private RequestCoordinator requestCoordinator;
  private final Map<String, String> vehicleNodeToTaxiName = new HashMap<>();
  private final Map<String, String> taxiNameToVehicleNodeId = new HashMap<>();
  private final Map<String, TopologyPeerView> topologyViewsByNodeId = new ConcurrentHashMap<>();
  private TopologyManager topologyManager;
  private final P2PNetworkSnapshotBuilder networkSnapshotBuilder = new P2PNetworkSnapshotBuilder();
  private final TaxiCountDerivationService taxiCountDerivationService = new TaxiCountDerivationService();
  private final CollectorNodeLifecycleManager collectorNodeLifecycleManager =
      new CollectorNodeLifecycleManager(NODE_ID_COLLECTOR_LOCAL, NODE_ID_COLLECTOR_PREFIX);
  private final LocalVehicleNodeManager localVehicleNodeManager = new LocalVehicleNodeManager(NODE_ID_VEHICLE_PREFIX);
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
  private static final String P2P_OVERLAY_MAX_DISTANCE_FACTOR = "p2pOverlayMaxDistanceFactor";
  private static final String P2P_OVERLAY_SHORTCUT_STRATEGY = "p2pOverlayShortcutStrategy";
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
  private static final String STATUS_OVERLAY_SHORTCUT_STRATEGY = "overlayShortcutStrategy";
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
  private static final String STATUS_OVERLAY_POSITION_TTL_TICKS = "overlayPositionTtlTicks";
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
  private static final String KEY_TAXI_COUNT = "taxiCount";
  private static final String NODE_ID_COLLECTOR_LOCAL = "sim-collector-local";
  private static final String NODE_ID_COLLECTOR_PREFIX = "sim-collector-";
  private static final String NODE_ID_VEHICLE_PREFIX = "vehicle-";
  private static final String TAXI_NAME_PREFIX = "t";
  private static final String EVENT_COLLECTOR_INITIALIZED = "collector-initialized";
  private static final String EVENT_WAITING_FOR_COMMIT = "waiting-for-commit";
  private static final String EVENT_TOPOLOGY_SCAN_REQUESTED_MANUAL = "topology-scan-requested-manual";
  private static final String EVENT_DERIVED_TAXI_COUNT_PREFIX = "derived-taxiCount-";
  private static final String EVENT_ASSIGNED_PREFIX = "assigned-";
  private static final int DEFAULT_VEHICLE_COMMIT_LEASE_TICKS = 20;
  private static final int DEFAULT_VEHICLE_REOFFER_INTERVAL_TICKS = 1;
  private static final int DEFAULT_VEHICLE_REQUEST_CACHE_TTL_TICKS = 120;
  private String collectorNodeId = "";
  private CommitHandler commitHandler;

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
        new AlgorithmParameter(P2P_OVERLAY_MAX_NEIGHBORS, 4),
        new AlgorithmParameter(P2P_OVERLAY_SHORTCUTS, 1),
        new AlgorithmParameter(P2P_OVERLAY_POSITION_TTL_TICKS, 200),
        new AlgorithmParameter(P2P_OVERLAY_MAX_DISTANCE_FACTOR, 1),
        new AlgorithmParameter(P2P_OVERLAY_SHORTCUT_STRATEGY, 1));
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
    setParameters(this.parameters);
    applyOverlayConfig(this.parameters);

    stepCounter = 0;
    // instantiate coordinator (uses suppliers to avoid stale references)
    requestCoordinator = new RequestCoordinator(
        () -> clientNode,
        () -> parameters,
        () -> stepCounter,
        runtimeState,
        rangeQuerySystem,
        taxiNameToVehicleNodeId,
        this::registerTaxiKnowledge,
        this::refreshStatus);
    commitHandler = new CommitHandler(
        runtimeState,
        vehicleNodeToTaxiName,
        taxiNameToVehicleNodeId,
        worldArg -> getEmptyTaxis(worldArg).stream().collect(Collectors.toMap(Taxi::getName, t -> t, (a, b) -> a, HashMap::new)),
        (taxi, client, worldArg) -> worldArg.mutate().planClientForTaxi(taxi, client, TargetList.sequentialOrders),
        this::refreshStatus,
        this::announceWinner);
    topologyManager = new TopologyManager(
        () -> clientNode,
        () -> network,
        () -> parameters,
        () -> stepCounter,
        () -> lastTopologyScanAtStep,
        (nodeId, data) -> topologyViewsByNodeId.put(nodeId, new TopologyPeerView(nodeId, data.role, data.neighborIds, data.shortcutNeighborIds)),
        (atStep, scanId) -> { lastTopologyScanAtStep = atStep; lastTopologyScanId = scanId; },
        this::refreshStatus);
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
    topologyManager.requestScanIfDue(true);
    refreshStatus(EVENT_COLLECTOR_INITIALIZED);
  }

  @Override
  protected AlgorithmResult nextStep(World world, Collection<Client> waitingClients) {
    stepCounter++;
    cleanupNoLongerWaiting(waitingClients);
    if (topologyManager != null) {
      topologyManager.requestScanIfDue(false);
    }

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
      syncLocalVehicleStates(world);
    }
    requestCoordinator.retriggerRequestsIfNeeded(waitingClients);
    requestCoordinator.publishNewRequests(world, waitingClients);
    processInbox(world, waitingClients);
    logPeriodicRuntimeStatus(world, waitingClients);
  }

  private void processInbox(World world, Collection<Client> waitingClients) {
    List<P2PMessage> delta = clientNode.drainInbox();
    if (delta.isEmpty()) {
      return;
    }

    int newMessages = delta.size();
    handleIncoming(world, waitingClients, delta);
    log.info(
        "[P2P-COLLECTOR] event t={} peers={} waiting={} newMessages={}",
        world.getCurrentTime(),
        network.peers().size(),
        waitingClients.size(),
        newMessages);
  }

  private void logPeriodicRuntimeStatus(World world, Collection<Client> waitingClients) {
    if (stepCounter % 100 != 0) {
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
      announceWinner(pending.requestId(), pending.committedVehicleNodeId());
      refreshStatus(EVENT_ASSIGNED_PREFIX + pending.requestId());
      applied++;
    }
    assignedClients.forEach(runtimeState::removePendingForClient);
    return applied;
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

  private void handleIncoming(World world, Collection<Client> waitingClients, List<P2PMessage> messages) {
    for (P2PMessage message : messages) {
      String topic = message.topic();
      if (P2PTopics.TOPOLOGY_SCAN_RESPONSE.equals(topic)) {
        if (topologyManager != null) {
          topologyManager.handleTopologyScanResponse(message);
        }
        continue;
      }
      if (P2PTopics.TOPOLOGY_SCAN_REQUEST.equals(topic)) {
        continue;
      }

      TaxiCollectorRuntimeState.PendingRequest pending = runtimeState.pendingForRequestId(message.requestId());
      if (pending == null) {
        continue;
      }

      if (P2PTopics.RIDE_COMMIT.equals(message.topic())) {
        handleCommit(message, pending, world, waitingClients);
      }
    }
  }

  private void handleCommit(P2PMessage message, TaxiCollectorRuntimeState.PendingRequest pending, World world, Collection<Client> waitingClients) {
    // Register taxi knowledge from the commit payload before delegating
    Map<String, String> payload = de.sikeller.aqs.p2p.service.KeyValuePayload.parse(message.payload());
    String vehicleNodeId = payload.getOrDefault(P2PPayloadKeys.VEHICLE, message.senderId());
    registerTaxiKnowledge(vehicleNodeId, pending.clientName());
    commitHandler.handleCommit(message, pending, world, waitingClients);
  }


  private void cleanupNoLongerWaiting(Collection<Client> waitingClients) {
    Set<String> waitingNames = waitingClients.stream().map(Client::getName).collect(Collectors.toSet());
    for (String clientName : runtimeState.pendingClientNamesSnapshot()) {
      if (!waitingNames.contains(clientName)) {
          runtimeState.pendingForClient(clientName);
          // no-op: offers are not stored centrally
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
    status.put(
        STATUS_OVERLAY_SHORTCUT_STRATEGY,
        System.getProperty(P2PSystemProperties.OVERLAY_SHORTCUT_STRATEGY, "kleinberg"));
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
        STATUS_OVERLAY_POSITION_TTL_TICKS,
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
    if (topologyManager != null) {
      topologyManager.requestScanIfDue(true);
    }
    refreshStatus(EVENT_TOPOLOGY_SCAN_REQUESTED_MANUAL);
    return true;
  }

  @Override
  public P2PNetworkSnapshot getP2PNetworkSnapshot() {
    if (clientNode == null || network == null) {
      return P2PNetworkSnapshot.empty();
    }

    // In embedded mode: read overlay neighbors directly from each local vehicle node.
    // This gives a live view without waiting for the topology scan inbox cycle.
    if (isEmbeddedSimulationMode() && !localVehicleNodesByTaxiName.isEmpty()) {
      refreshTopologyFromLocalNodes();
    } else {
      if (topologyManager != null) {
        topologyManager.requestScanIfDue(false);
        topologyManager.upsertLocalTopologyView();
      }
    }

    String localNodeId = clientNode.descriptor().id();
    Map<String, P2PNetworkSnapshotBuilder.TopologyViewData> topologyViews =
        topologyViewsByNodeId.values().stream()
            .collect(
                Collectors.toMap(
                    view -> view.nodeId,
                    view ->
                        new P2PNetworkSnapshotBuilder.TopologyViewData(
                            view.nodeId, view.role, view.neighborIds, view.shortcutNeighborIds)));
    return networkSnapshotBuilder.build(
        localNodeId,
        clientNode.descriptor().role().name(),
        network.peers(),
        topologyViews);
  }

  /** Reads each local vehicle's current overlay selection directly (embedded mode only). */
  private void refreshTopologyFromLocalNodes() {
    localVehicleNodesByTaxiName.forEach((taxiName, vehicleNode) -> {
      String vehicleNodeId = taxiNameToVehicleNodeId.getOrDefault(taxiName, NODE_ID_VEHICLE_PREFIX + taxiName);
      de.sikeller.aqs.p2p.service.AbstractP2PNodeService.OverlayNeighborSnapshot snap =
          vehicleNode.overlayNeighborSnapshot();
      topologyViewsByNodeId.put(
          vehicleNodeId,
          new TopologyPeerView(vehicleNodeId, "VEHICLE", snap.neighborIds(), snap.shortcutIds()));
    });
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
    collectorNodeLifecycleManager.ensureCollectorNode(config, resolveMulticastGroup(config));
    network = collectorNodeLifecycleManager.network();
    clientNode = collectorNodeLifecycleManager.clientNode();
    collectorNodeId = collectorNodeLifecycleManager.collectorNodeId();
  }

  private void ensureLocalVehicleNodes(World world) {
    localVehicleNodeManager.ensureLocalVehicleNodes(
        world,
        isEmbeddedSimulationMode(),
        network,
        localVehicleNodesByTaxiName,
        vehicleNodeToTaxiName,
        taxiNameToVehicleNodeId);
  }

  private void syncLocalVehicleStates(World world) {
    localVehicleNodeManager.syncLocalVehicleStates(
        world,
        isEmbeddedSimulationMode(),
        network,
        stepCounter,
        localVehicleNodesByTaxiName,
        vehicleNodeToTaxiName,
        taxiNameToVehicleNodeId);
  }

  private void applyOverlayConfig(Map<String, Integer> config) {
    int minNeighbors =
        Math.max(
            0,
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
    // 0 = ring, 1 = kleinberg (default); only set if no external JVM property is already present
    if (System.getProperty(P2PSystemProperties.OVERLAY_SHORTCUT_STRATEGY) == null) {
      int strategyFlag = config.getOrDefault(P2P_OVERLAY_SHORTCUT_STRATEGY, 1);
      System.setProperty(
          P2PSystemProperties.OVERLAY_SHORTCUT_STRATEGY, strategyFlag == 0 ? "ring" : "kleinberg");
    }
  }

  private long resolveOverlayMaxDistance() {
    return resolveOverlayMaxDistance(parameters);
  }

  private long resolveOverlayMaxDistance(Map<String, Integer> config) {
    long fixedRadius = Math.max(1, config.getOrDefault(P2P_FIXED_SEARCH_RADIUS, 5000));
    int factor = Math.max(1, config.getOrDefault(P2P_OVERLAY_MAX_DISTANCE_FACTOR, 1));
    return fixedRadius * factor;
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
    List<NodeDescriptor> vehicles = taxiCountDerivationService.discoverVehiclePeers(network, waitMs, 1500);

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

  /** Broadcasts RIDE_ASSIGNED to all vehicle peers so losers free their busy-lease immediately. */
  private void announceWinner(String requestId, String winnerVehicleNodeId) {
    if (clientNode == null || requestId == null || requestId.isBlank()) {
      return;
    }
    clientNode.announceWinner(requestId, winnerVehicleNodeId);
  }

  private void stopNetworkNode() {
    localVehicleNodeManager.stopLocalVehicleNodes(localVehicleNodesByTaxiName, taxiNameToVehicleNodeId);
    collectorNodeLifecycleManager.stopCollectorNode();
    network = collectorNodeLifecycleManager.network();
    clientNode = collectorNodeLifecycleManager.clientNode();
    collectorNodeId = collectorNodeLifecycleManager.collectorNodeId();
    topologyViewsByNodeId.clear();
    lastTopologyScanAtStep = Long.MIN_VALUE;
    lastTopologyScanId = "";
    lastStatusEvent = VALUE_UNKNOWN;
    runtimeState.clear();
  }

  private static final class TopologyPeerView {
    final String nodeId;
    final String role;
    final Set<String> neighborIds;
    final Set<String> shortcutNeighborIds;

    private TopologyPeerView(String nodeId, String role, Set<String> neighborIds, Set<String> shortcutNeighborIds) {
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


