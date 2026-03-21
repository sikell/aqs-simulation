package de.sikeller.aqs.p2p.service;

import de.sikeller.aqs.p2p.api.NodeDescriptor;
import de.sikeller.aqs.p2p.api.NodeRole;
import de.sikeller.aqs.p2p.api.P2PMessage;
import de.sikeller.aqs.p2p.api.P2PNetwork;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ClientP2PService extends AbstractP2PNodeService {
  public static final String TOPIC_RIDE_REQUEST = "ride.request";
  public static final String TOPIC_RIDE_ACCEPT = "ride.accept";
  public static final String TOPIC_RIDE_COMMIT = "ride.commit";

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
    payload.put("schemaVersion", String.valueOf(P2PMessage.SCHEMA_VERSION));
    payload.put("requestId", requestId);
    payload.put("originNode", descriptor().id());
    payload.put("hopsRemaining", String.valueOf(Math.max(0, maxForwardHops)));
    if (requestGeoHash != null && !requestGeoHash.isBlank()) {
      payload.put("requestGeoHash", requestGeoHash);
    }
    payload.put("from", from);
    payload.put("to", to);
    if (extraPayloadFields != null) {
      extraPayloadFields.forEach(
          (key, value) -> {
            if (key != null && !key.isBlank() && value != null) {
              payload.put(key, value);
            }
          });
    }
    publishMessage(
        TOPIC_RIDE_REQUEST,
        KeyValuePayload.write(payload),
        requestId,
        "",
        targetFilter);
    return requestId;
  }

  public void acceptOffer(String vehicleNodeId, String requestId) {
    Map<String, String> payload = new LinkedHashMap<>();
    payload.put("schemaVersion", String.valueOf(P2PMessage.SCHEMA_VERSION));
    payload.put("requestId", requestId);
    payload.put("client", descriptor().id());
    sendToMessage(vehicleNodeId, TOPIC_RIDE_ACCEPT, KeyValuePayload.write(payload), requestId, requestId);
  }

  public String requestTopologyScan() {
    String scanId = UUID.randomUUID().toString();
    Map<String, String> payload = new LinkedHashMap<>();
    payload.put("scanId", scanId);
    payload.put("requester", descriptor().id());
    publishMessage(
        TOPIC_TOPOLOGY_SCAN_REQUEST,
        KeyValuePayload.write(payload),
        scanId,
        scanId,
        node -> !node.id().equals(descriptor().id()));
    return scanId;
  }

  @Override
  protected void onMessage(P2PMessage message) {
    if (TOPIC_RIDE_COMMIT.equals(message.topic())) {
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

