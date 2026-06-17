package de.sikeller.aqs.taxi.algorithm.collector;

import de.sikeller.aqs.model.Client;
import de.sikeller.aqs.model.Taxi;
import de.sikeller.aqs.model.World;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import de.sikeller.aqs.p2p.service.util.TriConsumer;
import lombok.extern.slf4j.Slf4j;

/** Handles commit messages and applies taxi assignments. */
@Slf4j
final class CommitHandler {
  private final TaxiCollectorRuntimeState runtimeState;
  private final Function<World, Map<String, Taxi>> emptyTaxisProvider;
  private final TriConsumer<Taxi, Client, World> applyAssignment;
  private final Consumer<String> refreshStatusCallback;

  /** Called with (requestId, winnerVehicleNodeId, client) after a successful assignment. */
  private final TriConsumer<String, String, Client> onAssignedCallback;

  CommitHandler(
      TaxiCollectorRuntimeState runtimeState,
      Function<World, Map<String, Taxi>> emptyTaxisProvider,
      TriConsumer<Taxi, Client, World> applyAssignment,
      Consumer<String> refreshStatusCallback,
      TriConsumer<String, String, Client> onAssignedCallback) {
    this.runtimeState = runtimeState;
    this.emptyTaxisProvider = emptyTaxisProvider;
    this.applyAssignment = applyAssignment;
    this.refreshStatusCallback = refreshStatusCallback;
    this.onAssignedCallback = onAssignedCallback;
  }

  void handleCommit(
      String vehicleNodeId, TaxiCollectorRuntimeState.PendingRequest pending, World world) {
    if (pending.committedVehicleNodeId() != null
        && !pending.committedVehicleNodeId().equals(vehicleNodeId)) {
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

    Map<String, Taxi> emptyTaxisByName = emptyTaxisProvider.apply(world);
    Taxi selectedTaxi = emptyTaxisByName.remove(vehicleNodeId);

    Client client =
        world.getClients().stream()
            .filter(c -> c.getName().equals(pending.clientName()))
            .findFirst()
            .orElse(null);
    if (client == null) {
      refreshStatusCallback.accept("commit-" + pending.requestId());
      return;
    }
    applyAssignment.accept(selectedTaxi, client, world);
    log.info(
        "[P2P-COLLECTOR] applied committed assignment requestId={} client={} vehicle={} taxiName={}",
        pending.requestId(),
        pending.clientName(),
        vehicleNodeId,
        selectedTaxi.getName());
    runtimeState.pending.remove(pending.clientName());
    refreshStatusCallback.accept("assigned-" + pending.requestId());
    onAssignedCallback.accept(pending.requestId(), vehicleNodeId, client);
  }
}
