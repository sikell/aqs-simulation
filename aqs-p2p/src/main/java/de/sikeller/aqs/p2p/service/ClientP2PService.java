package de.sikeller.aqs.p2p.service;

import de.sikeller.aqs.p2p.api.NodeDescriptor;
import de.sikeller.aqs.p2p.api.NodeRole;
import de.sikeller.aqs.p2p.api.P2PMessage;
import de.sikeller.aqs.p2p.api.P2PNetwork;
import de.sikeller.aqs.p2p.api.P2PTopics;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ClientP2PService extends AbstractP2PNodeService {
  private final ConcurrentMap<String, String> acceptedVehicleByRequestId = new ConcurrentHashMap<>();

  public ClientP2PService(String nodeId, P2PNetwork network) {
    super(new NodeDescriptor(nodeId, NodeRole.CLIENT), network);
  }

  public String requestRide(String from, String to) {
    return requestRide(from, to, node -> !node.id().equals(descriptor().id()), 0, "");
  }

  public String requestRide(String from, String to, Predicate<NodeDescriptor> targetFilter) {
    return requestRide(from, to, targetFilter, 0, "");
  }

  public String requestRide(
      String from,
      String to,
      Predicate<NodeDescriptor> targetFilter,
      int maxForwardHops,
      String requestGeoHash) {
    return requestRide(from, to, targetFilter, maxForwardHops, requestGeoHash, Map.of());
  }

  public String requestRide(
      String from,
      String to,
      Predicate<NodeDescriptor> targetFilter,
      int maxForwardHops,
      String requestGeoHash,
      Map<String, String> extraPayloadFields) {
    String requestId = UUID.randomUUID().toString();
    Map<String, String> payload = new LinkedHashMap<>();
    payload.put("originNode", descriptor().id());
    payload.put("hopsRemaining", String.valueOf(Math.max(0, maxForwardHops)));
    if (extraPayloadFields != null) {
      extraPayloadFields.forEach(
          (key, value) -> {
            if (key != null && !key.isBlank() && value != null) {
              payload.put(key, value);
            }
          });
    }
    publishMessage(
        P2PTopics.RIDE_REQUEST,
        KeyValuePayload.write(payload),
        requestId,
        "",
        targetFilter);
    return requestId;
  }

  public void acceptOffer(String vehicleNodeId, String requestId) {
    if (vehicleNodeId == null || vehicleNodeId.isBlank() || requestId == null || requestId.isBlank()) {
      return;
    }

    String alreadyAccepted = acceptedVehicleByRequestId.putIfAbsent(requestId, vehicleNodeId);
    if (alreadyAccepted != null) {
      return;
    }

    sendToMessage(vehicleNodeId, P2PTopics.RIDE_ACCEPT, "", requestId, requestId);
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
    if (P2PTopics.RIDE_OFFER.equals(message.topic())) {
      Map<String, String> offer = KeyValuePayload.parse(message.payload());
      String requestId =
          offer.getOrDefault(
              "requestId",
              message.requestId() == null ? "" : message.requestId());
      String vehicleNodeId = offer.getOrDefault("vehicle", message.senderId());
      if (!requestId.isBlank() && vehicleNodeId != null && !vehicleNodeId.isBlank()) {
        // First offer wins for each request; later offers are ignored.
        acceptOffer(vehicleNodeId, requestId);
      }
      return;
    }

    if (P2PTopics.RIDE_COMMIT.equals(message.topic())) {
      if (message.requestId() != null && !message.requestId().isBlank()) {
        acceptedVehicleByRequestId.remove(message.requestId());
      }
      log.info(
          "Client node {} commit received requestId={} correlationId={} payload={}",
          descriptor().id(),
          message.requestId(),
          message.correlationId(),
          message.payload());
      return;
    }

    log.info(
        "Client node {} received: {} -> {}",
        descriptor().id(),
        message.topic(),
        message.payload());
  }
}

