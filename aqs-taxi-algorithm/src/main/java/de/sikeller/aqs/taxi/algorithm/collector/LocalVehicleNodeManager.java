package de.sikeller.aqs.taxi.algorithm.collector;

import de.sikeller.aqs.model.Taxi;
import de.sikeller.aqs.model.World;
import de.sikeller.aqs.model.SpawnScenario;
import de.sikeller.aqs.p2p.api.P2PNetwork;
import de.sikeller.aqs.p2p.service.VehicleP2PService;
import de.sikeller.aqs.p2p.service.position.SharedPositionManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/** Manages local embedded vehicle nodes and their lifecycle in one place. */
@Slf4j
public class LocalVehicleNodeManager {

  @FunctionalInterface
  interface VehicleNodeFactory {
    VehicleP2PService create(String vehicleNodeId, P2PNetwork network);
  }

  /** Callback for direct commits from embedded vehicles: (requestId, vehicleNodeId, taxiName). */
  @FunctionalInterface
  public interface DirectCommitReceiver {
    void onCommit(String requestId, String vehicleNodeId, String taxiName);
  }

  private final String vehicleNodePrefix;
  private final VehicleNodeFactory vehicleNodeFactory;
  private final Map<String, SyncedTaxiState> lastSyncedTaxiStates = new ConcurrentHashMap<>();
  private final SharedPositionManager sharedPositionManager = new SharedPositionManager();
  private SpawnScenario lastAppliedSpawnScenario;
  private int lastAppliedMapMaxX = Integer.MIN_VALUE;
  private int lastAppliedMapMaxY = Integer.MIN_VALUE;
  private volatile DirectCommitReceiver directCommitReceiver;

  private record SyncedTaxiState(boolean available, int x, int y) {}

  public LocalVehicleNodeManager(String vehicleNodePrefix) {
    this(vehicleNodePrefix, VehicleP2PService::new);
  }

  LocalVehicleNodeManager(String vehicleNodePrefix, VehicleNodeFactory vehicleNodeFactory) {
    this.vehicleNodePrefix = vehicleNodePrefix;
    this.vehicleNodeFactory = vehicleNodeFactory;
  }

  public void setDirectCommitReceiver(DirectCommitReceiver receiver) {
    this.directCommitReceiver = receiver;
  }

  public void ensureLocalVehicleNodes(
      World world,
      boolean embeddedMode,
      P2PNetwork network,
      SpawnScenario spawnScenario,
      Map<String, VehicleP2PService> localVehicleNodesByTaxiName,
      Map<String, String> vehicleNodeToTaxiName,
      Map<String, String> taxiNameToVehicleNodeId) {
    if (!embeddedMode || network == null) {
      return;
    }

    Set<String> activeTaxiNames =
        world.getTaxis().stream().map(Taxi::getName).collect(Collectors.toSet());
    if (localVehicleNodesByTaxiName.keySet().equals(activeTaxiNames)) {
      return;
    }

    world.getTaxis().stream()
        .sorted(Comparator.comparing(Taxi::getName))
        .forEach(
            taxi -> {
              if (localVehicleNodesByTaxiName.containsKey(taxi.getName())) {
                return;
              }
              createAndRegisterVehicleNode(
                  taxi, world, network, spawnScenario,
                  localVehicleNodesByTaxiName, vehicleNodeToTaxiName, taxiNameToVehicleNodeId);
            });

    List<String> toRemove = new ArrayList<>();
    for (String taxiName : localVehicleNodesByTaxiName.keySet()) {
      if (!activeTaxiNames.contains(taxiName)) {
        toRemove.add(taxiName);
      }
    }
    for (String taxiName : toRemove) {
      VehicleP2PService node = localVehicleNodesByTaxiName.remove(taxiName);
      if (node == null) {
        continue;
      }
      vehicleNodeToTaxiName.remove(node.descriptor().id());
      taxiNameToVehicleNodeId.remove(taxiName);
      lastSyncedTaxiStates.remove(taxiName);
      node.stop();
    }
  }

  public void syncLocalVehicleStates(
      World world,
      boolean embeddedMode,
      P2PNetwork network,
      SpawnScenario spawnScenario,
      long stepCounter,
      Map<String, VehicleP2PService> localVehicleNodesByTaxiName,
      Map<String, String> vehicleNodeToTaxiName,
      Map<String, String> taxiNameToVehicleNodeId) {
    if (!embeddedMode) {
      return;
    }

    applySharedNodeSettingsIfNeeded(world, spawnScenario, localVehicleNodesByTaxiName);

    List<Taxi> taxis = new ArrayList<>(world.getTaxis());
    for (Taxi taxi : taxis) {
      if (!localVehicleNodesByTaxiName.containsKey(taxi.getName())) {
        createAndRegisterVehicleNode(
            taxi, world, network, spawnScenario,
            localVehicleNodesByTaxiName, vehicleNodeToTaxiName, taxiNameToVehicleNodeId);
      }
    }

    if (taxis.isEmpty()) {
      return;
    }

    // Update shared position manager once with all positions (O(n))
    Map<String, de.sikeller.aqs.model.Position> allPositions = new HashMap<>(taxis.size());
    for (Taxi taxi : taxis) {
      String vehicleNodeId = taxiNameToVehicleNodeId.get(taxi.getName());
      if (vehicleNodeId != null) {
        allPositions.put(vehicleNodeId, taxi.getPosition());
      }
    }
    sharedPositionManager.updateSharedPositions(allPositions, stepCounter);

    for (Taxi taxi : taxis) {
      syncTaxiState(stepCounter, localVehicleNodesByTaxiName, taxi);
    }
  }

  private void syncTaxiState(long stepCounter, Map<String, VehicleP2PService> nodesSnapshot,
      Taxi taxi) {
    VehicleP2PService node = nodesSnapshot.get(taxi.getName());
    if (node == null) {
      return;
    }
    SyncedTaxiState nextState =
        new SyncedTaxiState(taxi.isEmpty(), taxi.getPosition().getX(), taxi.getPosition().getY());
    SyncedTaxiState previousState = lastSyncedTaxiStates.get(taxi.getName());
    if (nextState.equals(previousState)) {
      node.advanceSimulationTick(stepCounter);
    } else {
      node.setSimulationState(nextState.available(), nextState.x(), nextState.y(), stepCounter);
      lastSyncedTaxiStates.put(taxi.getName(), nextState);
    }
  }

  private void createAndRegisterVehicleNode(
      Taxi taxi, World world, P2PNetwork network, SpawnScenario spawnScenario,
      Map<String, VehicleP2PService> localVehicleNodesByTaxiName,
      Map<String, String> vehicleNodeToTaxiName,
      Map<String, String> taxiNameToVehicleNodeId) {
    String vehicleNodeId = vehicleNodePrefix + taxi.getName();
    VehicleP2PService vehicleNode = vehicleNodeFactory.create(vehicleNodeId, network);
    vehicleNode.setEmbeddedMode(true);
    vehicleNode.setSuppressPositionBroadcast(true);
    vehicleNode.setSharedPositionManager(sharedPositionManager);
    if (directCommitReceiver != null) {
      vehicleNode.setDirectCommitCallback(directCommitReceiver::onCommit);
    }
    vehicleNode.setPeerResolver(peerId -> {
      String peerTaxi = vehicleNodeToTaxiName.get(peerId);
      return peerTaxi != null ? localVehicleNodesByTaxiName.get(peerTaxi) : null;
    });
    vehicleNode.start();
    vehicleNode.setMapBounds(world.getSize().getMaxX(), world.getSize().getMaxY());
    vehicleNode.setSpawnScenario(spawnScenario);
    localVehicleNodesByTaxiName.put(taxi.getName(), vehicleNode);
    vehicleNodeToTaxiName.put(vehicleNodeId, taxi.getName());
    taxiNameToVehicleNodeId.put(taxi.getName(), vehicleNodeId);
    lastSyncedTaxiStates.remove(taxi.getName());
    log.info("[P2P-COLLECTOR] created vehicle node taxi={} nodeId={}", taxi.getName(), vehicleNodeId);
  }

  private void applySharedNodeSettingsIfNeeded(
      World world,
      SpawnScenario spawnScenario,
      Map<String, VehicleP2PService> localVehicleNodesByTaxiName) {
    if (localVehicleNodesByTaxiName.isEmpty()) {
      lastAppliedSpawnScenario = spawnScenario;
      lastAppliedMapMaxX = world.getSize().getMaxX();
      lastAppliedMapMaxY = world.getSize().getMaxY();
      return;
    }

    int maxX = world.getSize().getMaxX();
    int maxY = world.getSize().getMaxY();
    if (maxX != lastAppliedMapMaxX || maxY != lastAppliedMapMaxY) {
      localVehicleNodesByTaxiName.values().forEach(node -> node.setMapBounds(maxX, maxY));
      lastAppliedMapMaxX = maxX;
      lastAppliedMapMaxY = maxY;
    }

    if (spawnScenario != lastAppliedSpawnScenario) {
      localVehicleNodesByTaxiName.values().forEach(node -> node.setSpawnScenario(spawnScenario));
      lastAppliedSpawnScenario = spawnScenario;
    }
  }

  public void stopLocalVehicleNodes(
      Map<String, VehicleP2PService> localVehicleNodesByTaxiName,
      Map<String, String> taxiNameToVehicleNodeId) {
    localVehicleNodesByTaxiName.values().forEach(VehicleP2PService::stop);
    localVehicleNodesByTaxiName.clear();
    taxiNameToVehicleNodeId.clear();
    lastSyncedTaxiStates.clear();
    lastAppliedSpawnScenario = null;
    lastAppliedMapMaxX = Integer.MIN_VALUE;
    lastAppliedMapMaxY = Integer.MIN_VALUE;
  }
}
