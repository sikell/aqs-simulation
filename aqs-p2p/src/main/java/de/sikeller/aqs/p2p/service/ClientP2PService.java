package de.sikeller.aqs.p2p.service;

import de.sikeller.aqs.p2p.api.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ClientP2PService extends AbstractP2PNodeService {
  private final NodeDescriptor nodeDescriptor;
  // Track requests that have been committed so further commits are ignored (first-come-first-serve)
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
      if (message.requestId() != null && !message.requestId().isBlank()) {
        // First commit wins; subsequent ones are silently discarded by the collector
        committedRequestIds.add(message.requestId());
      }
      log.info(
          "Client node {} commit received requestId={} payload={}",
          descriptor().id(),
          message.requestId(),
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

