package de.sikeller.aqs.p2p.service.messaging;

import java.util.function.Predicate;

/** High-level message publishing abstraction for P2P node service. */
public interface MessagePublisher {
  void publish(String topic, String payload);

  void publish(
      String topic,
      String payload,
      String requestId,
      String correlationId,
      Predicate<de.sikeller.aqs.p2p.api.NodeDescriptor> targetFilter);

  void sendTo(
      String targetNodeId, String topic, String payload, String requestId, String correlationId);
}
