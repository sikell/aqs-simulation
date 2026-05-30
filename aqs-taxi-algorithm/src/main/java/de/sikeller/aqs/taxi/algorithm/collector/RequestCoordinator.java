package de.sikeller.aqs.taxi.algorithm.collector;

import de.sikeller.aqs.model.Client;
import de.sikeller.aqs.model.Taxi;
import de.sikeller.aqs.model.World;
import de.sikeller.aqs.p2p.api.NodeDescriptor;
import de.sikeller.aqs.p2p.api.NodeRole;
import de.sikeller.aqs.p2p.api.P2PPayloadKeys;
import de.sikeller.aqs.p2p.service.ClientP2PService;
import de.sikeller.aqs.p2p.service.VehicleP2PService;
import de.sikeller.aqs.taxi.algorithm.distributed.rqs.RangeQuerySystem;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Handles request publication and retrigger logic for the collector.
 */
final class RequestCoordinator {


  private static final String KEY_P2P_REQUEST_REPUBLISH_TICKS = "p2pRequestRepublishTicks";
  private static final String KEY_P2P_FIXED_SEARCH_RADIUS = "p2pFixedSearchRadius";
  private static final String KEY_P2P_REQUEST_FORWARD_HOPS = "p2pRequestForwardHops";
  private static final String KEY_P2P_EMBEDDED = "p2pEmbeddedSimulation";
  private static final String EVENT_REQUEST_PUBLISHED_PREFIX = "request-published-";

  private final Supplier<ClientP2PService> clientNodeSupplier;
  private final Supplier<Map<String, Integer>> parametersSupplier;
  private final LongSupplier stepCounterSupplier;
  private final TaxiCollectorRuntimeState runtimeState;
  private final RangeQuerySystem rangeQuerySystem;
  private final Map<String, String> taxiNameToVehicleNodeId;
  private final BiConsumer<String, String> registerTaxiKnowledgeCallback;
  private final Consumer<String> refreshStatusCallback;
  /** Resolves a vehicle node ID to the local VehicleP2PService (embedded mode only). */
  private final Function<String, VehicleP2PService> vehicleNodeResolver;

  RequestCoordinator(
      Supplier<ClientP2PService> clientNodeSupplier,
      Supplier<Map<String, Integer>> parametersSupplier,
      LongSupplier stepCounterSupplier,
      TaxiCollectorRuntimeState runtimeState,
      RangeQuerySystem rangeQuerySystem,
      Map<String, String> taxiNameToVehicleNodeId,
      BiConsumer<String, String> registerTaxiKnowledgeCallback,
      Consumer<String> refreshStatusCallback,
      Function<String, VehicleP2PService> vehicleNodeResolver) {
    this.clientNodeSupplier = clientNodeSupplier;
    this.parametersSupplier = parametersSupplier;
    this.stepCounterSupplier = stepCounterSupplier;
    this.runtimeState = runtimeState;
    this.rangeQuerySystem = rangeQuerySystem;
    this.taxiNameToVehicleNodeId = taxiNameToVehicleNodeId;
    this.registerTaxiKnowledgeCallback = registerTaxiKnowledgeCallback;
    this.refreshStatusCallback = refreshStatusCallback;
    this.vehicleNodeResolver = vehicleNodeResolver;
  }

  public void publishNewRequests(World world, Collection<Client> waitingClients) {
    ClientP2PService clientNode = clientNodeSupplier.get();
    if (clientNode == null) {
      return;
    }

    Map<String, Integer> parameters = parametersSupplier.get();
    long stepCounter = stepCounterSupplier.getAsLong();
    double searchRadius = Math.max(1, parameters.getOrDefault(KEY_P2P_FIXED_SEARCH_RADIUS, 5000));
    boolean embedded = parameters.getOrDefault(KEY_P2P_EMBEDDED, 1) == 1;
    List<Client> unassignedWaitingClients =
        waitingClients.stream()
            .filter(client -> runtimeState.pendingForClient(client.getName()) == null)
            .toList();
    if (unassignedWaitingClients.isEmpty()) {
      return;
    }

    Map<String, Set<String>> seedVehicleNodeIdsByClientName =
        unassignedWaitingClients.stream()
            .collect(
                Collectors.toMap(
                    Client::getName,
                    client ->
                        resolveInitialSeedVehicleNodeIds(world, client, searchRadius, parameters),
                    (a, b) -> a,
                    LinkedHashMap::new));

    String collectorNodeId = clientNode.descriptor().id();

    for (Client client : unassignedWaitingClients) {
      Set<String> seedVehicleNodeIds =
          seedVehicleNodeIdsByClientName.getOrDefault(client.getName(), Set.of());
      if (seedVehicleNodeIds.isEmpty()) {
        continue;
      }
      seedVehicleNodeIds.forEach(
          vehicleNodeId -> registerTaxiKnowledgeCallback.accept(vehicleNodeId, client.getName()));

      String requestId;

      if (embedded && vehicleNodeResolver != null) {
        // Direct delivery: bypass P2P network
        requestId = UUID.randomUUID().toString();
        runtimeState.putPendingRequest(requestId, client.getName(), stepCounter);
        int requestForwardHops = Math.max(0, parameters.getOrDefault(KEY_P2P_REQUEST_FORWARD_HOPS, 2));
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put(P2PPayloadKeys.ORIGIN_NODE, collectorNodeId);
        payload.put(P2PPayloadKeys.HOPS_REMAINING, String.valueOf(requestForwardHops));
        payload.put(P2PPayloadKeys.CLIENT_NAME, client.getName());
        payload.put(P2PPayloadKeys.REQUEST_X, String.valueOf(client.getPosition().getX()));
        payload.put(P2PPayloadKeys.REQUEST_Y, String.valueOf(client.getPosition().getY()));
        payload.put(P2PPayloadKeys.SEARCH_RADIUS, String.valueOf((int) Math.round(searchRadius)));
        for (String vehicleNodeId : seedVehicleNodeIds) {
          VehicleP2PService vehicle = vehicleNodeResolver.apply(vehicleNodeId);
          if (vehicle != null) {
            vehicle.deliverRideRequestDirect(requestId, collectorNodeId, payload);
          }
        }
      } else {
        // P2P network path (LAN mode)
        Predicate<NodeDescriptor> effectiveFilter =
            node -> node.role() == NodeRole.VEHICLE && seedVehicleNodeIds.contains(node.id());
        Map<String, String> extraPayload = new LinkedHashMap<>();
        extraPayload.put(P2PPayloadKeys.CLIENT_NAME, client.getName());
        extraPayload.put(P2PPayloadKeys.REQUEST_X, String.valueOf(client.getPosition().getX()));
        extraPayload.put(P2PPayloadKeys.REQUEST_Y, String.valueOf(client.getPosition().getY()));
        extraPayload.put(P2PPayloadKeys.SEARCH_RADIUS, String.valueOf((int) Math.round(searchRadius)));
        int requestForwardHops = Math.max(0, parameters.getOrDefault(KEY_P2P_REQUEST_FORWARD_HOPS, 2));
        requestId = clientNode.requestRide(
            client.getPosition().toString(),
            client.getTarget().toString(),
            effectiveFilter,
            requestForwardHops,
            extraPayload);
        runtimeState.putPendingRequest(requestId, client.getName(), stepCounter);
      }

      refreshStatusCallback.accept(EVENT_REQUEST_PUBLISHED_PREFIX + requestId);
    }
  }

  public void retriggerRequestsIfNeeded(Collection<Client> waitingClients) {
    long stepCounter = stepCounterSupplier.getAsLong();
    int republishTicks =
        Math.max(1, parametersSupplier.get().getOrDefault(KEY_P2P_REQUEST_REPUBLISH_TICKS, 3));
    for (Client client : waitingClients) {
      TaxiCollectorRuntimeState.PendingRequest pending =
          runtimeState.pendingForClient(client.getName());
      if (pending == null || pending.isCommitted()) {
        continue;
      }

      if (pending.state() == TaxiCollectorRuntimeState.RequestState.PUBLISHED
          && stepCounter - pending.lastPublishedStep() >= republishTicks) {
        runtimeState.removePendingForClient(client.getName());
      }
    }
  }

  private Set<String> resolveInitialSeedVehicleNodeIds(
      World world, Client client, double searchRadius, Map<String, Integer> parameters) {
    if (world == null
        || (parameters.getOrDefault(KEY_P2P_EMBEDDED, 1) == 1 && taxiNameToVehicleNodeId.isEmpty())) {
      return Set.of();
    }

    Set<Taxi> taxisInRange =
        rangeQuerySystem.findTaxisInRange(
            world, client.getPosition(), client.getTarget(), searchRadius);
    if (taxisInRange.isEmpty()) {
      return Set.of();
    }
    return taxisInRange.stream()
        .map(Taxi::getName)
        .map(taxiNameToVehicleNodeId::get)
        .filter(s -> s != null && !s.isBlank())
        .collect(Collectors.toSet());
  }
}
