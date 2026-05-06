package de.sikeller.aqs.p2p.transport.inmemory;

import de.sikeller.aqs.p2p.api.NodeDescriptor;
import de.sikeller.aqs.p2p.api.P2PMessage;
import de.sikeller.aqs.p2p.api.P2PNetwork;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class InMemoryP2PNetwork implements P2PNetwork {
  private final Map<String, NodeDescriptor> peersById = new ConcurrentHashMap<>();
  private final Map<String, Consumer<P2PMessage>> handlersById = new ConcurrentHashMap<>();

  @Override
  public void join(NodeDescriptor node, Consumer<P2PMessage> messageHandler) {
    peersById.put(node.id(), node);
    handlersById.put(node.id(), messageHandler);
  }

  @Override
  public void leave(String nodeId) {
    peersById.remove(nodeId);
    handlersById.remove(nodeId);
  }

  @Override
  public void sendTo(String targetNodeId, P2PMessage message) {
    var handler = handlersById.get(targetNodeId);
    if (handler != null) {
      handler.accept(message);
    }
  }

  @Override
  public void broadcast(P2PMessage message, Predicate<NodeDescriptor> targetFilter) {
    for (NodeDescriptor peer : peersById.values()) {
      if (targetFilter.test(peer)) {
        sendTo(peer.id(), message);
      }
    }
  }

  @Override
  public Set<NodeDescriptor> peers() {
    return Set.copyOf(peersById.values());
  }
}
