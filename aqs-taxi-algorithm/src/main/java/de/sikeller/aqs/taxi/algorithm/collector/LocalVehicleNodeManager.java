package de.sikeller.aqs.taxi.algorithm.collector;

import de.sikeller.aqs.model.Taxi;
import de.sikeller.aqs.model.World;
import de.sikeller.aqs.p2p.api.P2PNetwork;
import de.sikeller.aqs.p2p.service.VehicleP2PService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/** Manages local embedded vehicle nodes and their lifecycle in one place. */
@Slf4j
public class LocalVehicleNodeManager {

  @FunctionalInterface
  interface VehicleNodeFactory {
    VehicleP2PService create(String vehicleNodeId, P2PNetwork network);
  }

  private final String vehicleNodePrefix;
  private final VehicleNodeFactory vehicleNodeFactory;

  public LocalVehicleNodeManager(String vehicleNodePrefix) {
    this(vehicleNodePrefix, VehicleP2PService::new);
  }

  LocalVehicleNodeManager(String vehicleNodePrefix, VehicleNodeFactory vehicleNodeFactory) {
    this.vehicleNodePrefix = vehicleNodePrefix;
    this.vehicleNodeFactory = vehicleNodeFactory;
  }

  public void ensureLocalVehicleNodes(
      World world,
      boolean embeddedMode,
      P2PNetwork network,
      Map<String, VehicleP2PService> localVehicleNodesByTaxiName,
      Map<String, String> vehicleNodeToTaxiName,
      Map<String, String> taxiNameToVehicleNodeId) {
    if (!embeddedMode || network == null) {
      return;
    }

    Set<String> activeTaxiNames = world.getTaxis().stream().map(Taxi::getName).collect(Collectors.toSet());
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
              String vehicleNodeId = vehicleNodePrefix + taxi.getName();
              VehicleP2PService vehicleNode = vehicleNodeFactory.create(vehicleNodeId, network);
              vehicleNode.start();
              localVehicleNodesByTaxiName.put(taxi.getName(), vehicleNode);
              vehicleNodeToTaxiName.put(vehicleNodeId, taxi.getName());
              taxiNameToVehicleNodeId.put(taxi.getName(), vehicleNodeId);
              log.info(
                  "[P2P-COLLECTOR] created local vehicle node taxi={} nodeId={}",
                  taxi.getName(),
                  vehicleNodeId);
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
      node.stop();
    }
  }

  public void syncLocalVehicleStates(
      World world,
      boolean embeddedMode,
      P2PNetwork network,
      long stepCounter,
      Map<String, VehicleP2PService> localVehicleNodesByTaxiName,
      Map<String, String> vehicleNodeToTaxiName,
      Map<String, String> taxiNameToVehicleNodeId) {
    if (!embeddedMode) {
      return;
    }

    for (Taxi taxi : world.getTaxis()) {
      VehicleP2PService node = localVehicleNodesByTaxiName.get(taxi.getName());
      if (node == null) {
        String vehicleNodeId = vehicleNodePrefix + taxi.getName();
        VehicleP2PService vehicleNode = vehicleNodeFactory.create(vehicleNodeId, network);
        vehicleNode.start();
        localVehicleNodesByTaxiName.put(taxi.getName(), vehicleNode);
        vehicleNodeToTaxiName.put(vehicleNodeId, taxi.getName());
        taxiNameToVehicleNodeId.put(taxi.getName(), vehicleNodeId);
        log.info(
            "[P2P-COLLECTOR] lazily created local vehicle node taxi={} nodeId={}",
            taxi.getName(),
            vehicleNodeId);
        node = vehicleNode;
      }
      node.setSimulationState(
          taxi.isEmpty(), taxi.getPosition().getX(), taxi.getPosition().getY(), stepCounter);
    }
  }

  public void stopLocalVehicleNodes(
      Map<String, VehicleP2PService> localVehicleNodesByTaxiName,
      Map<String, String> taxiNameToVehicleNodeId) {
    localVehicleNodesByTaxiName.values().forEach(VehicleP2PService::stop);
    localVehicleNodesByTaxiName.clear();
    taxiNameToVehicleNodeId.clear();
  }
}

