package de.sikeller.aqs.p2p.service;

import de.sikeller.aqs.p2p.api.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ClientP2PService extends AbstractP2PNodeService {
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
    String requestId = UUID.randomUUID().toString();
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
    if (requestId == null || requestId.isBlank()) {
      return;
    }
    Map<String, String> payload = new LinkedHashMap<>();
    payload.put(P2PPayloadKeys.REQUEST_ID, requestId);
    if (winnerVehicleId != null && !winnerVehicleId.isBlank()) {
      payload.put(P2PPayloadKeys.WINNER_VEHICLE, winnerVehicleId);
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

  public String requestTopologyScan() {
    String scanId = UUID.randomUUID().toString();
    publishMessage(
        P2PTopics.TOPOLOGY_SCAN_REQUEST,
        "",
        scanId,
        scanId,
        node -> !node.id().equals(descriptor().id()));
    return scanId;
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
