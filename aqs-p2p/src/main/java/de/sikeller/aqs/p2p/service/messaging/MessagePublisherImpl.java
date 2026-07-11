package de.sikeller.aqs.p2p.service.messaging;

import de.sikeller.aqs.p2p.api.*;
import de.sikeller.aqs.p2p.util.P2PRunContext;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default MessagePublisher implementation using the P2PNetwork and overlay ID provider.
 */
public class MessagePublisherImpl implements MessagePublisher {
  private static final Logger log = LoggerFactory.getLogger(MessagePublisherImpl.class);
  private final NodeDescriptor descriptor;
  private final P2PNetwork network;
  private final BiFunction<String, List<NodeDescriptor>, Set<String>> overlayIdProvider;

  public MessagePublisherImpl(
      NodeDescriptor descriptor,
      P2PNetwork network,
      BiFunction<String, List<NodeDescriptor>, Set<String>> overlayIdProvider) {
    this.descriptor = descriptor;
    this.network = network;
    this.overlayIdProvider = overlayIdProvider;
  }

  @Override
  public void publish(String topic, String payload) {
    publish(topic, payload, null, null, node -> !node.id().equals(descriptor.id()));
  }

  @Override
  public void publish(
      String topic,
      String payload,
      String requestId,
      String correlationId,
      Predicate<NodeDescriptor> targetFilter) {
    P2PRunContext.measureCommunication(
        () -> {
          P2PMessage msg =
              requestId == null
                  ? P2PMessage.now(descriptor.id(), topic, payload)
                  : P2PMessage.now(
                      descriptor.id(),
                      topic,
                      payload,
                      requestId,
                      correlationId == null ? "" : correlationId);
          var peers =
              network.peers().stream()
                  .filter(peer -> !descriptor.id().equals(peer.id()))
                  .toList();
          final Set<String> overlayIds = computeOverlayIds(topic, peers);
          if (overlayIds == null || overlayIds.isEmpty()) {
            network.broadcast(msg, targetFilter);
          } else {
            network.broadcast(
                msg, node -> targetFilter.test(node) && overlayIds.contains(node.id()));
          }
        });
  }

  private Set<String> computeOverlayIds(String topic, List<NodeDescriptor> peers) {
    try {
      return overlayIdProvider.apply(topic, peers);
    } catch (Exception ex) {
      log.warn("[P2P-OVERLAY] overlay id computation failed topic={}: {}", topic, ex.toString());
      return Set.of();
    }
  }

  @Override
  public void sendTo(
      String targetNodeId, String topic, String payload, String requestId, String correlationId) {
    P2PRunContext.measureCommunication(
        () -> {
          P2PMessage msg =
              requestId == null
                  ? P2PMessage.now(descriptor.id(), topic, payload)
                  : P2PMessage.now(
                      descriptor.id(),
                      topic,
                      payload,
                      requestId,
                      correlationId == null ? "" : correlationId);
          network.sendTo(targetNodeId, msg);
        });
  }
}
