package de.sikeller.aqs.p2p.service.messaging;

import de.sikeller.aqs.p2p.api.*;
import de.sikeller.aqs.p2p.service.overlay.OverlaySelector;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Default MessagePublisher implementation that uses the provided P2PNetwork
 * and an OverlaySelector to compute target peers.
 */
public class MessagePublisherImpl implements MessagePublisher {
  private final NodeDescriptor descriptor;
  private final P2PNetwork network;
  private final OverlaySelector overlaySelector;

  public MessagePublisherImpl(NodeDescriptor descriptor, P2PNetwork network, OverlaySelector overlaySelector) {
    this.descriptor = descriptor;
    this.network = network;
    this.overlaySelector = overlaySelector;
  }

  @Override
  public void publish(String topic, String payload) {
    publish(topic, payload, null, null, node -> !node.id().equals(descriptor.id()));
  }

  @Override
  public void publish(String topic, String payload, String requestId, String correlationId, Predicate<NodeDescriptor> targetFilter) {
    P2PMessage msg = requestId == null ? P2PMessage.now(descriptor.id(), topic, payload)
        : P2PMessage.now(descriptor.id(), topic, payload, requestId, correlationId == null ? "" : correlationId);
    var peers = network.peers().stream().filter(peer -> !descriptor.id().equals(peer.id())).toList();
    final Set<String> overlayIds = computeOverlayIds(topic, peers);
    try {
      // debug: log overlay ids and intended targets
      var targets = peers.stream().filter(node -> targetFilter.test(node) && overlayIds.contains(node.id())).map(NodeDescriptor::id).toList();
      System.out.println("[DEBUG] MessagePublisher.publish topic=" + topic + " overlayTargets=" + targets + " overlayIds=" + overlayIds);
    } catch (Throwable ignored) {}
    if (overlayIds == null || overlayIds.isEmpty()) {
      // fallback: no overlay filtering -> broadcast to all peers matching targetFilter
      network.broadcast(msg, targetFilter);
    } else {
      network.broadcast(msg, node -> targetFilter.test(node) && overlayIds.contains(node.id()));
    }
  }

  private Set<String> computeOverlayIds(String topic, java.util.List<NodeDescriptor> peers) {
    try {
      return overlaySelector.overlayNeighborIds(topic, peers);
    } catch (Exception ignored) {
      return java.util.Collections.emptySet();
    }
  }

  @Override
  public void sendTo(String targetNodeId, String topic, String payload, String requestId, String correlationId) {
    P2PMessage msg = requestId == null ? P2PMessage.now(descriptor.id(), topic, payload)
        : P2PMessage.now(descriptor.id(), topic, payload, requestId, correlationId == null ? "" : correlationId);
    network.sendTo(targetNodeId, msg);
  }
}

