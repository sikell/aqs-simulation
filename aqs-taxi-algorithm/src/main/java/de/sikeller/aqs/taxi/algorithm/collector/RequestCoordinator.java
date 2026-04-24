package de.sikeller.aqs.taxi.algorithm.collector;

import de.sikeller.aqs.model.Client;
import de.sikeller.aqs.model.World;
import de.sikeller.aqs.p2p.api.NodeDescriptor;
import de.sikeller.aqs.p2p.api.NodeRole;
import de.sikeller.aqs.p2p.api.P2PPayloadKeys;
import de.sikeller.aqs.p2p.service.ClientP2PService;
import de.sikeller.aqs.taxi.algorithm.distributed.rqs.RangeQuerySystem;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Low-risk extraction of request publication / retrigger logic from the collector.
 * This class holds no independent state; it operates on the shared {@link TaxiCollectorRuntimeState}.
 */
final class RequestCoordinator {

  private static final Logger log = LoggerFactory.getLogger(RequestCoordinator.class);

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

  RequestCoordinator(
      Supplier<ClientP2PService> clientNodeSupplier,
      Supplier<Map<String, Integer>> parametersSupplier,
      LongSupplier stepCounterSupplier,
      TaxiCollectorRuntimeState runtimeState,
      RangeQuerySystem rangeQuerySystem,
      Map<String, String> taxiNameToVehicleNodeId,
      BiConsumer<String, String> registerTaxiKnowledgeCallback,
      Consumer<String> refreshStatusCallback) {
    this.clientNodeSupplier = clientNodeSupplier;
    this.parametersSupplier = parametersSupplier;
    this.stepCounterSupplier = stepCounterSupplier;
    this.runtimeState = runtimeState;
    this.rangeQuerySystem = rangeQuerySystem;
    this.taxiNameToVehicleNodeId = taxiNameToVehicleNodeId;
    this.registerTaxiKnowledgeCallback = registerTaxiKnowledgeCallback;
    this.refreshStatusCallback = refreshStatusCallback;
  }

  public void publishNewRequests(World world, Collection<Client> waitingClients) {
    ClientP2PService clientNode = clientNodeSupplier.get();
    if (clientNode == null) {
      return;
    }

    long stepCounter = stepCounterSupplier.getAsLong();
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
      seedVehicleNodeIds.forEach(vehicleNodeId -> registerTaxiKnowledgeCallback.accept(vehicleNodeId, client.getName()));
      Predicate<NodeDescriptor> effectiveFilter = node -> node.role() == NodeRole.VEHICLE && seedVehicleNodeIds.contains(node.id());
      int requestForwardHops = Math.max(0, parametersSupplier.get().getOrDefault(KEY_P2P_REQUEST_FORWARD_HOPS, 2));

      Map<String, String> extraPayload = new java.util.LinkedHashMap<>();
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
      refreshStatusCallback.accept(EVENT_REQUEST_PUBLISHED_PREFIX + requestId);
    }
  }

  public void retriggerRequestsIfNeeded(Collection<Client> waitingClients) {
    long stepCounter = stepCounterSupplier.getAsLong();
    int republishTicks = Math.max(1, parametersSupplier.get().getOrDefault(KEY_P2P_REQUEST_REPUBLISH_TICKS, 3));
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

  private double resolveSearchRadius() {
    return Math.max(1, parametersSupplier.get().getOrDefault(KEY_P2P_FIXED_SEARCH_RADIUS, 5000));
  }

  private Set<String> resolveInitialSeedVehicleNodeIds(World world, Client client, double searchRadius) {
    if (world == null || (parametersSupplier.get().getOrDefault(KEY_P2P_EMBEDDED, 1) == 1 && taxiNameToVehicleNodeId.isEmpty())) {
      return Set.of();
    }

    Set<de.sikeller.aqs.model.Taxi> taxisInRange =
        rangeQuerySystem.findTaxisInRange(world, client.getPosition(), client.getTarget(), searchRadius);
    if (taxisInRange.isEmpty()) {
      return Set.of();
    }
    return taxisInRange.stream()
        .map(de.sikeller.aqs.model.Taxi::getName)
        .map(taxiNameToVehicleNodeId::get)
        .filter(s -> s != null && !s.isBlank())
        .collect(Collectors.toSet());
  }
}

