package de.sikeller.aqs.p2p.service;

import de.sikeller.aqs.p2p.api.NodeDescriptor;
import de.sikeller.aqs.p2p.api.NodeRole;
import de.sikeller.aqs.p2p.api.P2PPayloadKeys;
import de.sikeller.aqs.p2p.api.P2PMessage;
import de.sikeller.aqs.p2p.api.P2PNetwork;
import de.sikeller.aqs.p2p.api.P2PTopics;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ClientP2PService extends AbstractP2PNodeService {
  private final NodeDescriptor nodeDescriptor;
  private final ConcurrentMap<String, String> acceptedVehicleByRequestId = new ConcurrentHashMap<>();
  // Track requests that have been committed so further offers are ignored
  private final Set<String> committedRequestIds = ConcurrentHashMap.newKeySet();

  public ClientP2PService(String nodeId, P2PNetwork network) {
    super(new NodeDescriptor(nodeId, NodeRole.CLIENT), network);
    this.nodeDescriptor = new NodeDescriptor(nodeId, NodeRole.CLIENT);
  }

  @Override
  public NodeDescriptor descriptor() {
    return nodeDescriptor;
  }

  public String requestRide(String from, String to) {
    return requestRide(from, to, node -> !node.id().equals(descriptor().id()), 0, "");
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
    payload.put(P2PPayloadKeys.ORIGIN_NODE, descriptor().id());
    payload.put(P2PPayloadKeys.HOPS_REMAINING, String.valueOf(Math.max(0, maxForwardHops)));
    if (from != null && !from.isBlank()) {
      payload.put(P2PPayloadKeys.FROM, from);
    }
    if (to != null && !to.isBlank()) {
      payload.put(P2PPayloadKeys.TO, to);
    }
    if (requestGeoHash != null && !requestGeoHash.isBlank()) {
      payload.put(P2PPayloadKeys.REQUEST_GEO_HASH, requestGeoHash);
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

    // If this request is already committed, ignore further accepts
    if (committedRequestIds.contains(requestId)) {
      log.debug("Client node {} ignoring acceptOffer for already committed requestId={}", descriptor().id(), requestId);
      return;
    }

    // Ensure only one accept is sent for a given requestId. Do NOT call sendToMessage
    // from inside computeIfAbsent's mapping function because that can cause a
    // re-entrant modification of this map (sendToMessage may synchronously trigger
    // onMessage which calls remove) and ConcurrentHashMap will throw
    // IllegalStateException: Recursive update. Use putIfAbsent and send the message
    // only when we successfully inserted the reservation.
    String previous = acceptedVehicleByRequestId.putIfAbsent(requestId, vehicleNodeId);
    if (previous == null) {
      // We successfully reserved this requestId -> send accept to vehicle
      sendToMessage(vehicleNodeId, P2PTopics.RIDE_ACCEPT, "", requestId, requestId);
    }
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
        // Ignore offers for requests already committed
        if (committedRequestIds.contains(requestId)) {
          log.debug("Client node {} ignoring offer for already committed requestId={} from={}", descriptor().id(), requestId, vehicleNodeId);
          return;
        }
        // First offer wins for each request; later offers are ignored.
        acceptOffer(vehicleNodeId, requestId);
      }
      return;
    }

    if (P2PTopics.RIDE_COMMIT.equals(message.topic())) {
      if (message.requestId() != null && !message.requestId().isBlank()) {
        String requestId = message.requestId();
        // Parse vehicle from payload if present, otherwise fall back to senderId
        String vehicleFromPayload = message.payload() == null || message.payload().isBlank()
            ? message.senderId()
            : KeyValuePayload.parse(message.payload()).getOrDefault(P2PPayloadKeys.VEHICLE, message.senderId());
        String recorded = acceptedVehicleByRequestId.get(requestId);
        if (recorded == null) {
          // No prior accepted vehicle recorded: mark committed and remove any stale mapping
          committedRequestIds.add(requestId);
          acceptedVehicleByRequestId.remove(requestId);
        } else if (recorded.equals(vehicleFromPayload)) {
          // Commit from the vehicle we accepted -> clear reservation and mark committed
          acceptedVehicleByRequestId.remove(requestId);
          committedRequestIds.add(requestId);
        } else {
          // Commit from an unexpected vehicle -> ignore to avoid double-commit
          log.info(
              "Client node {} ignoring commit from unexpected vehicle={} for requestId={} (expected={})",
              descriptor().id(),
              vehicleFromPayload,
              requestId,
              recorded);
          return;
        }
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

