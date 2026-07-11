package de.sikeller.aqs.p2p.service;

import de.sikeller.aqs.p2p.api.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ClientP2PService extends AbstractP2PNodeService {
  private final AtomicLong rideRequestSequence = new AtomicLong();
  private final AtomicLong topologyScanSequence = new AtomicLong();

  public ClientP2PService(String nodeId, P2PNetwork network) {
    super(new NodeDescriptor(nodeId, NodeRole.CLIENT), network);
  }

  public String requestRide(String from, String to) {
    return requestRide(from, to, node -> !node.id().equals(descriptor().id()), 0);
  }

  public String requestRide(
      String from, String to, Predicate<NodeDescriptor> targetFilter, int maxForwardHops) {
    return requestRide(from, to, targetFilter, maxForwardHops, Map.of());
  }

  public String requestRide(
      String from,
      String to,
      Predicate<NodeDescriptor> targetFilter,
      int maxForwardHops,
      Map<String, String> extraPayloadFields) {
    String requestId = deterministicId("ride", rideRequestSequence.incrementAndGet());
    Map<String, String> payload = new LinkedHashMap<>();
    payload.put(P2PPayloadKeys.ORIGIN_NODE, descriptor().id());
    payload.put(P2PPayloadKeys.HOPS_REMAINING, String.valueOf(Math.max(0, maxForwardHops)));
    if (from != null && !from.isBlank()) {
      payload.put(P2PPayloadKeys.FROM, from);
    }
    if (to != null && !to.isBlank()) {
      payload.put(P2PPayloadKeys.TO, to);
    }
    if (extraPayloadFields != null) {
      extraPayloadFields.forEach(
          (key, value) -> {
            if (key != null && !key.isBlank() && value != null) {
              payload.put(key, value);
            }
          });
    }
    publishMessage(
        P2PTopics.RIDE_REQUEST, KeyValuePayload.write(payload), requestId, "", targetFilter);
    return requestId;
  }

  /**
   * Announces the winning vehicle for a committed ride request to all vehicle peers. Losers can
   * immediately drop the request instead of waiting for their busy-lease TTL to expire.
   */
  public void announceWinner(String requestId, String winnerVehicleId) {
    announceWinner(requestId, winnerVehicleId, null, null);
  }

  public void announceWinner(
      String requestId, String winnerVehicleId, Integer pickupX, Integer pickupY) {
    if (requestId == null || requestId.isBlank()) {
      return;
    }
    Map<String, String> payload = new LinkedHashMap<>();
    payload.put(P2PPayloadKeys.REQUEST_ID, requestId);
    if (winnerVehicleId != null && !winnerVehicleId.isBlank()) {
      payload.put(P2PPayloadKeys.WINNER_VEHICLE, winnerVehicleId);
    }
    if (pickupX != null && pickupY != null) {
      payload.put(P2PPayloadKeys.PICKUP_X, String.valueOf(pickupX));
      payload.put(P2PPayloadKeys.PICKUP_Y, String.valueOf(pickupY));
    }
    publishMessage(
        P2PTopics.RIDE_ASSIGNED,
        KeyValuePayload.write(payload),
        requestId,
        requestId,
        node -> node.role() == NodeRole.VEHICLE);
    log.info(
        "Client node {} announced winner requestId={} winner={}",
        descriptor().id(),
        requestId,
        winnerVehicleId);
  }

  public void sendVehicleState(
      String vehicleNodeId,
      boolean available,
      int x,
      int y,
      long simulationTick,
      int mapMaxX,
      int mapMaxY,
      String spawnScenario) {
    sendVehicleState(
        vehicleNodeId,
        available,
        x,
        y,
        simulationTick,
        mapMaxX,
        mapMaxY,
        spawnScenario,
        Map.of());
  }

  public void sendVehicleState(
      String vehicleNodeId,
      boolean available,
      int x,
      int y,
      long simulationTick,
      int mapMaxX,
      int mapMaxY,
      String spawnScenario,
      Map<String, String> config) {
    if (vehicleNodeId == null || vehicleNodeId.isBlank()) {
      return;
    }
    Map<String, String> payload = new LinkedHashMap<>();
    payload.put(P2PPayloadKeys.AVAILABLE, String.valueOf(available));
    payload.put(P2PPayloadKeys.POSITION_X, String.valueOf(x));
    payload.put(P2PPayloadKeys.POSITION_Y, String.valueOf(y));
    payload.put(P2PPayloadKeys.POSITION_TICK, String.valueOf(simulationTick));
    payload.put(P2PPayloadKeys.MAP_MAX_X, String.valueOf(mapMaxX));
    payload.put(P2PPayloadKeys.MAP_MAX_Y, String.valueOf(mapMaxY));
    if (spawnScenario != null && !spawnScenario.isBlank()) {
      payload.put(P2PPayloadKeys.SPAWN_SCENARIO, spawnScenario);
    }
    if (config != null) {
      config.forEach(
          (key, value) -> {
            if (key != null && !key.isBlank() && value != null) {
              payload.put(key, value);
            }
          });
    }
    sendToMessage(
        vehicleNodeId,
        P2PTopics.VEHICLE_STATE,
        KeyValuePayload.write(payload),
        "state-" + simulationTick + "-" + vehicleNodeId,
        "");
  }

  public String requestTopologyScan() {
    String scanId = deterministicId("scan", topologyScanSequence.incrementAndGet());
    publishMessage(
        P2PTopics.TOPOLOGY_SCAN_REQUEST,
        "",
        scanId,
        scanId,
        node -> !node.id().equals(descriptor().id()));
    return scanId;
  }

  private String deterministicId(String kind, long sequence) {
    return String.format(Locale.ROOT, "%s-%s-%020d", descriptor().id(), kind, sequence);
  }

  @Override
  protected void onMessage(P2PMessage message) {
    if (P2PTopics.RIDE_COMMIT.equals(message.topic())) {
      log.debug(
          "Client node {} commit received requestId={} payload={}",
          descriptor().id(),
          message.requestId(),
          message.payload());
      return;
    }

    log.info(
        "Client node {} received: {} -> {}", descriptor().id(), message.topic(), message.payload());
  }
}
