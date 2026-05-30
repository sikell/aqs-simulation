package de.sikeller.aqs.p2p.transport.inmemory;

import de.sikeller.aqs.p2p.api.NodeDescriptor;
import de.sikeller.aqs.p2p.api.P2PMessage;
import de.sikeller.aqs.p2p.api.P2PNetwork;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * In-memory P2P network for embedded simulation mode.
 * All message dispatch is synchronous and sequential.
 */
public class InMemoryP2PNetwork implements P2PNetwork {

  private final Map<String, NodeDescriptor> peersById = new ConcurrentHashMap<>();
  private final Map<String, Consumer<P2PMessage>> handlersById = new ConcurrentHashMap<>();
  private volatile Set<NodeDescriptor> peersSnapshot = Set.of();

  private void refreshPeersSnapshot() {
    peersSnapshot = Set.copyOf(peersById.values());
  }

  @Override
  public void join(NodeDescriptor node, Consumer<P2PMessage> messageHandler) {
    peersById.put(node.id(), node);
    handlersById.put(node.id(), messageHandler);
    refreshPeersSnapshot();
  }

  @Override
  public void leave(String nodeId) {
    peersById.remove(nodeId);
    handlersById.remove(nodeId);
    refreshPeersSnapshot();
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
    Set<NodeDescriptor> peers = peersSnapshot;
    if (peers.isEmpty()) {
      return;
    }
    for (NodeDescriptor peer : peers) {
      if (targetFilter.test(peer)) {
        sendTo(peer.id(), message);
      }
    }
  }

  @Override
  public Set<NodeDescriptor> peers() {
    return peersSnapshot;
  }
}
