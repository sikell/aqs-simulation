package de.sikeller.aqs.taxi.algorithm.collector;

import de.sikeller.aqs.model.Client;
import de.sikeller.aqs.model.Taxi;
import de.sikeller.aqs.model.World;
import de.sikeller.aqs.p2p.api.P2PMessage;
import de.sikeller.aqs.p2p.api.P2PPayloadKeys;
import de.sikeller.aqs.p2p.service.KeyValuePayload;
import de.sikeller.aqs.taxi.algorithm.collector.api.CollectorRuntimeStateView;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Encapsulates commit handling logic extracted from the collector.
 */
final class CommitHandler {

  private static final Logger log = LoggerFactory.getLogger(CommitHandler.class);

  @FunctionalInterface
  interface TriConsumer<A, B, C> {
    void accept(A a, B b, C c);
  }

  private static final String NODE_ID_VEHICLE_PREFIX = "vehicle-";

  private final CollectorRuntimeStateView runtimeState;
  private final Map<String, String> vehicleNodeToTaxiName;
  private final Map<String, String> taxiNameToVehicleNodeId;
  private final Function<World, Map<String, Taxi>> emptyTaxisProvider;
  private final TriConsumer<Taxi, Client, World> applyAssignment;
  private final Consumer<String> refreshStatusCallback;
  /** Called with (requestId, winnerVehicleNodeId) after a successful assignment. */
  private final BiConsumer<String, String> onAssignedCallback;

  CommitHandler(
      CollectorRuntimeStateView runtimeState,
      Map<String, String> vehicleNodeToTaxiName,
      Map<String, String> taxiNameToVehicleNodeId,
      Function<World, Map<String, Taxi>> emptyTaxisProvider,
      TriConsumer<Taxi, Client, World> applyAssignment,
      Consumer<String> refreshStatusCallback,
      BiConsumer<String, String> onAssignedCallback) {
    this.runtimeState = runtimeState;
    this.vehicleNodeToTaxiName = vehicleNodeToTaxiName;
    this.taxiNameToVehicleNodeId = taxiNameToVehicleNodeId;
    this.emptyTaxisProvider = emptyTaxisProvider;
    this.applyAssignment = applyAssignment;
    this.refreshStatusCallback = refreshStatusCallback;
    this.onAssignedCallback = onAssignedCallback;
  }

  void handleCommit(P2PMessage message, TaxiCollectorRuntimeState.PendingRequest pending, World world, Collection<Client> waitingClients) {
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

    String taxiNameRaw = payload.get(P2PPayloadKeys.TAXI_NAME);
    if (taxiNameRaw != null && !taxiNameRaw.isBlank()) {
      String normalizedTaxiName = taxiNameRaw.startsWith(NODE_ID_VEHICLE_PREFIX)
          ? taxiNameRaw.substring(NODE_ID_VEHICLE_PREFIX.length())
          : taxiNameRaw;
      vehicleNodeToTaxiName.put(vehicleNodeId, normalizedTaxiName);
      taxiNameToVehicleNodeId.put(normalizedTaxiName, vehicleNodeId);
      log.info("[P2P-COLLECTOR] mapped vehicleNodeId={} -> taxiName={} from commit payload", vehicleNodeId, normalizedTaxiName);
    }
    pending.markCommitted(vehicleNodeId);
    log.info("[P2P-COLLECTOR] commit requestId={} client={} vehicle={}", pending.requestId(), pending.clientName(), vehicleNodeId);

    String mappedTaxiName = vehicleNodeToTaxiName.getOrDefault(vehicleNodeId, vehicleNodeId);
    Map<String, Taxi> emptyTaxisByName = emptyTaxisProvider.apply(world);

    Taxi selectedTaxi = emptyTaxisByName.remove(mappedTaxiName);
    if (selectedTaxi == null) {
      // Fallback: resolve via taxi knowledge
      selectedTaxi = resolveViaKnowledge(pending, emptyTaxisByName);
    }

    if (selectedTaxi != null) {
      Client client = findClientByName(waitingClients, pending.clientName());
      if (client != null) {
        applyAssignment.accept(selectedTaxi, client, world);
        log.info(
            "[P2P-COLLECTOR] applied committed assignment requestId={} client={} vehicle={} taxiName={}",
               pending.requestId(),
               pending.clientName(),
               vehicleNodeId,
               selectedTaxi.getName());
         runtimeState.removePendingForClient(pending.clientName());
         refreshStatusCallback.accept("assigned-" + pending.requestId());
         onAssignedCallback.accept(pending.requestId(), vehicleNodeId);
        return;
      }
    }

    refreshStatusCallback.accept("commit-" + pending.requestId());
  }

  private Taxi resolveViaKnowledge(TaxiCollectorRuntimeState.PendingRequest pending, Map<String, Taxi> emptyTaxisByName) {
    var knowledge = runtimeState.taxiKnowledgeSnapshot(Set.of(pending.clientName()));
    if (knowledge.isEmpty()) {
      return null;
    }
    String candidateTaxi = knowledge.keySet().iterator().next();
    String candidateVehicleNodeId = taxiNameToVehicleNodeId.getOrDefault(candidateTaxi, candidateTaxi);
    String candidateMappedTaxiName = vehicleNodeToTaxiName.getOrDefault(candidateVehicleNodeId, candidateTaxi);
    return emptyTaxisByName.remove(candidateMappedTaxiName);
  }

  private static Client findClientByName(Collection<Client> clients, String name) {
    for (Client c : clients) {
      if (c.getName().equals(name)) {
        return c;
      }
    }
    return null;
  }
}
