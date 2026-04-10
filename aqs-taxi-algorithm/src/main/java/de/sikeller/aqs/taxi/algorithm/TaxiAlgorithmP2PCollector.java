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
import de.sikeller.aqs.p2p.service.AbstractP2PNodeService;
import de.sikeller.aqs.p2p.service.ClientP2PService;
import de.sikeller.aqs.p2p.service.VehicleP2PService;
import de.sikeller.aqs.p2p.service.KeyValuePayload;
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
  private static final String UNKNOWN_ROLE = "UNKNOWN";
  private static final String OVERLAY_MAX_NEIGHBORS_PROPERTY = "aqs.p2p.overlay.maxNeighbors";
  private static final String OVERLAY_SHORTCUTS_PROPERTY = "aqs.p2p.overlay.shortcuts";
  private static final String OVERLAY_COLLECTOR_NODE_ID_PROPERTY = "aqs.p2p.overlay.collectorNodeId";
  private static final String EMBEDDED_MODE_PROPERTY = "p2pEmbeddedSimulation";
  private static final String P2P_VEHICLE_OPEN_CLIENT_STRATEGY = "p2pVehicleOpenClientStrategy";
  private static final String P2P_RQS_ROUTE_PROXIMITY_MODE = "p2pRqsRouteProximityMode";
  private static final String P2P_VEHICLE_COMMIT_LEASE_TICKS = "p2pVehicleCommitLeaseTicks";
  private static final String P2P_VEHICLE_REBID_INTERVAL_TICKS = "p2pVehicleRebidIntervalTicks";
  private static final String P2P_VEHICLE_REQUEST_CACHE_TTL_TICKS = "p2pVehicleRequestCacheTtlTicks";
  private String collectorNodeId = "";

  @Override
  public SimulationConfiguration getParameters() {
    return new SimulationConfiguration(
        new AlgorithmParameter("p2pTcpPort", 46100),
        new AlgorithmParameter("p2pDiscoveryPort", 45892),
        new AlgorithmParameter("p2pMulticastA", 239),
        new AlgorithmParameter("p2pMulticastB", 255),
        new AlgorithmParameter("p2pMulticastC", 42),
        new AlgorithmParameter("p2pMulticastD", 99),
        new AlgorithmParameter("p2pDiscoveryWaitMs", 6000),
        new AlgorithmParameter(P2P_OFFER_COLLECTION_TICKS, 0),
        new AlgorithmParameter(P2P_TOPOLOGY_SCAN_TICKS, 20),
        new AlgorithmParameter("p2pRequestForwardHops", 2),
        new AlgorithmParameter(P2P_REQUEST_REPUBLISH_TICKS, 3),
        new AlgorithmParameter("p2pFixedSearchRadius", 5000),
        new AlgorithmParameter(P2P_RQS_ROUTE_PROXIMITY_MODE, 0),
        new AlgorithmParameter(P2P_VEHICLE_COMMIT_LEASE_TICKS, 20),
        new AlgorithmParameter(P2P_VEHICLE_REBID_INTERVAL_TICKS, 1),
        new AlgorithmParameter(P2P_VEHICLE_REQUEST_CACHE_TTL_TICKS, 120),
        new AlgorithmParameter("CalculateFullTaxis", 0),
        new AlgorithmParameter(P2P_VEHICLE_OPEN_CLIENT_STRATEGY, 1),
        new AlgorithmParameter("p2pEmbeddedSimulation", 1),
        new AlgorithmParameter("p2pOverlayMaxNeighbors", 3),
        new AlgorithmParameter("p2pOverlayShortcuts", 1));
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
    refreshStatus("collector-initialized");
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
        long newOffers = delta.stream().filter(m -> VehicleP2PService.TOPIC_RIDE_OFFER.equals(m.topic())).count();
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

      // Run offer selection every step so pending offers can mature even without new messages.
      acceptBestOffersWhenReady();

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
        refreshStatus("assigned-" + pending.requestId);
        return ok();
      }
    }
    refreshStatus("waiting-for-commit");
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
      int requestForwardHops = Math.max(0, parameters.getOrDefault("p2pRequestForwardHops", 2));

      Map<String, String> extraPayload = new LinkedHashMap<>();
      extraPayload.put("simTick", String.valueOf(stepCounter));
      extraPayload.put("clientName", client.getName());
      extraPayload.put("requestX", String.valueOf(client.getPosition().getX()));
      extraPayload.put("requestY", String.valueOf(client.getPosition().getY()));
      extraPayload.put("targetX", String.valueOf(client.getTarget().getX()));
      extraPayload.put("targetY", String.valueOf(client.getTarget().getY()));
      extraPayload.put("searchRadius", String.valueOf((int) Math.round(searchRadius)));
      extraPayload.put("scope", "rqs-seeded");

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
      refreshStatus("request-published-" + requestId);
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

      if (pending.acceptedVehicleNodeId == null
          && pending.bestOfferVehicleNodeId == null
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
    int strategyCode = config.getOrDefault(P2P_VEHICLE_OPEN_CLIENT_STRATEGY, 1);
    String strategy = strategyCode == 0 ? "greedy" : "nearest";
    System.setProperty(VehicleP2PService.VEHICLE_OPEN_REQUEST_STRATEGY_PROPERTY, strategy);
    System.setProperty(
        "aqs.p2p.vehicle.commitLeaseTicks",
        String.valueOf(Math.max(1, config.getOrDefault(P2P_VEHICLE_COMMIT_LEASE_TICKS, 20))));
    System.setProperty(
        "aqs.p2p.vehicle.rebidMinIntervalTicks",
        String.valueOf(Math.max(0, config.getOrDefault(P2P_VEHICLE_REBID_INTERVAL_TICKS, 1))));
    System.setProperty(
        "aqs.p2p.vehicle.requestCacheTtlTicks",
        String.valueOf(Math.max(1, config.getOrDefault(P2P_VEHICLE_REQUEST_CACHE_TTL_TICKS, 120))));
  }

  private double resolveSearchRadius() {
      return Math.max(1, parameters.getOrDefault("p2pFixedSearchRadius", 5000));
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
      if (AbstractP2PNodeService.TOPIC_TOPOLOGY_SCAN_RESPONSE.equals(message.topic())) {
        handleTopologyScanResponse(message);
        continue;
      }
      if (AbstractP2PNodeService.TOPIC_TOPOLOGY_SCAN_REQUEST.equals(message.topic())) {
        continue;
      }

      PendingRequest pending = pendingByRequestId.get(message.requestId());
      if (pending == null) {
        continue;
      }

      if (VehicleP2PService.TOPIC_RIDE_OFFER.equals(message.topic())) {
        handleOffer(message, pending);
      } else if (ClientP2PService.TOPIC_RIDE_COMMIT.equals(message.topic())) {
        handleCommit(message, pending);
      }
    }
  }

  private void handleTopologyScanResponse(P2PMessage message) {
    Map<String, String> payload = KeyValuePayload.parse(message.payload());
    String nodeId = payload.getOrDefault("node", message.senderId());
    String role = payload.getOrDefault("role", UNKNOWN_ROLE);
    Set<String> neighbors = parseNeighbors(payload.get("neighbors"));

    topologyViewsByNodeId.put(
        nodeId,
        new TopologyPeerView(nodeId, role, neighbors));
    refreshStatus("topology-response-" + nodeId);
  }

  private void handleOffer(P2PMessage message, PendingRequest pending) {
    if (pending.acceptedVehicleNodeId != null || pending.committedVehicleNodeId != null) {
      return;
    }

    Map<String, String> payload = KeyValuePayload.parse(message.payload());
    String vehicleNodeId = payload.getOrDefault("vehicle", message.senderId());
    registerTaxiKnowledge(vehicleNodeId, pending.clientName);
    int etaSeconds = parseEtaSeconds(payload.get("etaSeconds"));

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
    refreshStatus("offer-tracked-" + pending.requestId);
  }

  private void handleCommit(P2PMessage message, PendingRequest pending) {
    Map<String, String> payload = KeyValuePayload.parse(message.payload());
    String vehicleNodeId = payload.getOrDefault("vehicle", message.senderId());
    if (pending.acceptedVehicleNodeId != null && !pending.acceptedVehicleNodeId.equals(vehicleNodeId)) {
      log.info(
          "[P2P-COLLECTOR] ignored commit requestId={} committedBy={} acceptedBy={}",
          pending.requestId,
          vehicleNodeId,
          pending.acceptedVehicleNodeId);
      return;
    }
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
    refreshStatus("commit-" + pending.requestId);
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

  private void acceptBestOffersWhenReady() {
    if (clientNode == null) {
      return;
    }

    long waitTicks = resolveOfferCollectionTicks();
    for (PendingRequest pending : pendingByRequestId.values()) {
      if (pending.acceptedVehicleNodeId != null || pending.bestOfferVehicleNodeId == null) {
        continue;
      }

      long elapsedTicks = Math.max(0, stepCounter - pending.firstOfferAtStep);
      if (elapsedTicks < waitTicks) {
        continue;
      }

      pending.acceptedVehicleNodeId = pending.bestOfferVehicleNodeId;
      clientNode.acceptOffer(pending.acceptedVehicleNodeId, pending.requestId);
      log.info(
          "[P2P-COLLECTOR] accepted best offer (selection=etaSeconds) requestId={} client={} vehicle={} etaSeconds={} collectedForTicks={}",
          pending.requestId,
          pending.clientName,
          pending.acceptedVehicleNodeId,
          pending.bestOfferEtaSeconds,
          elapsedTicks);
      refreshStatus("offer-accepted-" + pending.requestId);
    }
  }

  private long resolveOfferCollectionTicks() {
    return Math.max(0, parameters.getOrDefault(P2P_OFFER_COLLECTION_TICKS, 0));
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
    refreshStatus("topology-scan-requested-manual");
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

    Set<String> edgeKeys = new HashSet<>();
    for (TopologyPeerView view : topologyViewsByNodeId.values()) {
      for (String neighborId : view.neighborIds) {
        String key = edgeKey(view.nodeId, neighborId);
        if (!key.isBlank()) {
          edgeKeys.add(key);
        }
      }
    }

    List<P2PNetworkEdgeSnapshot> edges =
        edgeKeys.stream()
            .sorted()
            .map(
                edgeKey -> {
                  int separator = edgeKey.indexOf('\u0000');
                  if (separator < 1 || separator >= edgeKey.length() - 1) {
                    return null;
                  }
                  String from = edgeKey.substring(0, separator);
                  String to = edgeKey.substring(separator + 1);
                  return new P2PNetworkEdgeSnapshot(from, to);
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
            neighbors));
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
        ? leftNodeId + "\u0000" + rightNodeId
        : rightNodeId + "\u0000" + leftNodeId;
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
        System.setProperty(OVERLAY_COLLECTOR_NODE_ID_PROPERTY, collectorNodeId);
      }
      return;
    }

    if (isEmbeddedSimulationMode(config)) {
      String nodeId = "sim-collector-local";
      collectorNodeId = nodeId;
      System.setProperty(OVERLAY_COLLECTOR_NODE_ID_PROPERTY, nodeId);
      network = new InMemoryP2PNetwork();
      clientNode = new ClientP2PService(nodeId, network);
      clientNode.start();
      log.info("P2P collector node started in LOCAL simulation mode: id={}", nodeId);
      return;
    }

    int tcpPort = config.getOrDefault("p2pTcpPort", 46100);
    int discoveryPort = config.getOrDefault("p2pDiscoveryPort", 45892);
    String multicastGroup = resolveMulticastGroup(config);
    String nodeId = "sim-collector-" + tcpPort;
    collectorNodeId = nodeId;
    System.setProperty(OVERLAY_COLLECTOR_NODE_ID_PROPERTY, nodeId);

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

      String vehicleNodeId = "vehicle-" + taxi.getName();
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
    int maxNeighbors = Math.max(1, config.getOrDefault("p2pOverlayMaxNeighbors", 3));
    int shortcuts = Math.max(0, config.getOrDefault("p2pOverlayShortcuts", 1));

    System.setProperty(OVERLAY_MAX_NEIGHBORS_PROPERTY, String.valueOf(maxNeighbors));
    System.setProperty(OVERLAY_SHORTCUTS_PROPERTY, String.valueOf(shortcuts));
  }

  private boolean isEmbeddedSimulationMode() {
    return isEmbeddedSimulationMode(parameters);
  }

  private boolean isEmbeddedSimulationMode(Map<String, Integer> config) {
    return config.getOrDefault(EMBEDDED_MODE_PROPERTY, 1) == 1;
  }

  private String resolveMulticastGroup(Map<String, Integer> config) {
    int a = octet(config, "p2pMulticastA", 239);
    int b = octet(config, "p2pMulticastB", 255);
    int c = octet(config, "p2pMulticastC", 42);
    int d = octet(config, "p2pMulticastD", 99);
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

    int waitMs = prepared.getOrDefault("p2pDiscoveryWaitMs", 6000);
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
      String taxiName = "t" + i;
      vehicleNodeToTaxiName.put(vehicles.get(i).id(), taxiName);
      taxiNameToVehicleNodeId.put(taxiName, vehicles.get(i).id());
    }

    prepared.put("taxiCount", vehicles.size());
    log.info(
        "[P2P-COLLECTOR] derived taxiCount={} from discovered vehicle peers={}",
        vehicles.size(),
        vehicles.stream().map(NodeDescriptor::id).toList());
    refreshStatus("derived-taxiCount-" + vehicles.size());
  }

  private long countVehiclePeers(Set<NodeDescriptor> peers) {
    return peers.stream().filter(peer -> peer.role() == NodeRole.VEHICLE).count();
  }

  private void refreshStatus(String event) {
    p2pStatus.put("mode", "P2P");
    p2pStatus.put("collectorNode", clientNode != null ? clientNode.descriptor().id() : "-");
    p2pStatus.put("knownPeers", String.valueOf(network != null ? network.peers().size() : 0));
    p2pStatus.put(
        "vehiclePeers",
        String.valueOf(network != null ? countVehiclePeers(network.peers()) : 0));
    p2pStatus.put("pendingRequests", String.valueOf(pendingByRequestId.size()));
    p2pStatus.put("mappedVehicles", String.valueOf(vehicleNodeToTaxiName.size()));
    p2pStatus.put("topologyViews", String.valueOf(topologyViewsByNodeId.size()));
    p2pStatus.put("topologyScanId", lastTopologyScanId == null || lastTopologyScanId.isBlank() ? "-" : lastTopologyScanId);
    p2pStatus.put("overlayMode", "SMALL_WORLD");
    p2pStatus.put("overlayMaxNeighbors", System.getProperty(OVERLAY_MAX_NEIGHBORS_PROPERTY, "3"));
    p2pStatus.put("overlayShortcuts", System.getProperty(OVERLAY_SHORTCUTS_PROPERTY, "1"));
    String fixedRadius = String.valueOf(parameters.getOrDefault("p2pFixedSearchRadius", 5000));
    p2pStatus.put("rqsFixedRadius", fixedRadius);
    p2pStatus.put("rqsCenter", "client");
    p2pStatus.put(
        "rqsRecognitionMode",
        parameters.getOrDefault(P2P_RQS_ROUTE_PROXIMITY_MODE, 0) == 0
            ? "taxi-position"
            : "taxi-route");
    p2pStatus.put(
        "vehicleClientSelection",
        parameters.getOrDefault(P2P_VEHICLE_OPEN_CLIENT_STRATEGY, 1) == 0 ? "greedy" : "nearest");
    p2pStatus.put("clientRangeFilter", "seed-only");
    p2pStatus.put("runtimeMode", isEmbeddedSimulationMode() ? "EMBEDDED" : "LAN");
    p2pStatus.put("localVehicleNodes", String.valueOf(localVehicleNodesByTaxiName.size()));
    p2pStatus.put("lastEvent", event);
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
      String configuredCollector = System.getProperty(OVERLAY_COLLECTOR_NODE_ID_PROPERTY, "");
      if (collectorNodeId.equals(configuredCollector)) {
        System.clearProperty(OVERLAY_COLLECTOR_NODE_ID_PROPERTY);
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
    private String acceptedVehicleNodeId;
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

    private TopologyPeerView(String nodeId, String role, Set<String> neighborIds) {
      this.nodeId = nodeId;
      this.role = role;
      this.neighborIds = neighborIds == null ? Set.of() : Set.copyOf(neighborIds);
    }
  }
}

