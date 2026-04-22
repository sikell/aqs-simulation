package de.sikeller.aqs.p2p.service.messaging;

import static org.junit.jupiter.api.Assertions.*;

import de.sikeller.aqs.p2p.api.NodeDescriptor;
import de.sikeller.aqs.p2p.api.NodeRole;
import de.sikeller.aqs.p2p.api.P2PNetwork;
import de.sikeller.aqs.p2p.api.P2PMessage;
import de.sikeller.aqs.p2p.service.overlay.OverlaySelector;
import de.sikeller.aqs.p2p.service.overlay.OverlaySelector.OverlaySelection;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class MessagePublisherImplTest {

  private static final class FakeNetwork implements P2PNetwork {
    private final Set<NodeDescriptor> peers;
    P2PMessage lastMessage;
    Set<String> lastTargets = Set.of();

    FakeNetwork(Set<NodeDescriptor> peers) {
      this.peers = peers;
    }

    @Override public void join(NodeDescriptor node, Consumer<P2PMessage> messageHandler) {}
    @Override public void leave(String nodeId) {}
    @Override public void sendTo(String targetNodeId, P2PMessage message) { this.lastMessage = message; }

    @Override
    public void broadcast(P2PMessage message, Predicate<NodeDescriptor> targetFilter) {
      this.lastMessage = message;
      Set<String> targets = new HashSet<>();
      for (NodeDescriptor nd : peers) {
        if (targetFilter.test(nd)) targets.add(nd.id());
      }
      this.lastTargets = targets;
    }

    @Override public Set<NodeDescriptor> peers() { return peers; }
  }

  @Test
  void publishBroadcastsToOverlayAndRespectsFilter() {
    NodeDescriptor self = new NodeDescriptor("vehicle-1", NodeRole.VEHICLE);
    NodeDescriptor v2 = new NodeDescriptor("vehicle-2", NodeRole.VEHICLE);
    NodeDescriptor c1 = new NodeDescriptor("client-1", NodeRole.CLIENT);
    Set<NodeDescriptor> peers = Set.of(self, v2, c1);

    FakeNetwork net = new FakeNetwork(peers);

    // simple overlay selector that returns all peers as neighbors
    OverlaySelector sel = (topic, peerList) -> new OverlaySelection(new ArrayList<>(peerList), Set.of());

    MessagePublisherImpl pub = new MessagePublisherImpl(self, net, sel);
    pub.publish("TEST_TOPIC", "payload");

    assertNotNull(net.lastMessage, "message should have been broadcast");
    // overlay includes all peers, but publisher should exclude self via default target filter
    assertTrue(net.lastTargets.contains("vehicle-2"));
    assertTrue(net.lastTargets.contains("client-1"));
    assertFalse(net.lastTargets.contains("vehicle-1"), "self should be excluded from targets");
  }
}

