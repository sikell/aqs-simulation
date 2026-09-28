package de.sikeller.aqs.taxi.algorithm.collector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.sikeller.aqs.p2p.api.NodeDescriptor;
import de.sikeller.aqs.p2p.api.NodeRole;
import de.sikeller.aqs.p2p.api.P2PMessage;
import de.sikeller.aqs.p2p.api.P2PNetwork;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class TaxiCountDerivationServiceTest {

  private final TaxiCountDerivationService service = new TaxiCountDerivationService();

  @Test
  void discoverVehiclePeersReturnsEmptyWhenNetworkMissing() {
    List<NodeDescriptor> result = service.discoverVehiclePeers(null, 100, 100);
    assertTrue(result.isEmpty());
  }

  @Test
  void discoverVehiclePeersFiltersAndSortsVehiclePeers() {
    StaticNetwork network =
        new StaticNetwork(
            Set.of(
                new NodeDescriptor("client-1", NodeRole.CLIENT),
                new NodeDescriptor("vehicle-b", NodeRole.VEHICLE),
                new NodeDescriptor("vehicle-a", NodeRole.VEHICLE)));

    List<NodeDescriptor> result = service.discoverVehiclePeers(network, 50, 0);

    assertEquals(2, result.size());
    assertEquals("vehicle-a", result.get(0).id());
    assertEquals("vehicle-b", result.get(1).id());
  }

  @Test
  void discoverVehiclePeersKeepsBestObservedCountWithinBudget() {
    ScriptedNetwork network =
        new ScriptedNetwork(
            List.of(
                Set.of(new NodeDescriptor("vehicle-0", NodeRole.VEHICLE)),
                Set.of(
                    new NodeDescriptor("vehicle-0", NodeRole.VEHICLE),
                    new NodeDescriptor("vehicle-1", NodeRole.VEHICLE)),
                Set.of(new NodeDescriptor("vehicle-0", NodeRole.VEHICLE))));

    List<NodeDescriptor> result = service.discoverVehiclePeers(network, 600, 1000);

    assertEquals(2, result.size());
    assertEquals("vehicle-0", result.get(0).id());
    assertEquals("vehicle-1", result.get(1).id());
  }

  private static final class StaticNetwork implements P2PNetwork {
    private final Set<NodeDescriptor> peers;

    private StaticNetwork(Set<NodeDescriptor> peers) {
      this.peers = peers;
    }

    @Override
    public void join(NodeDescriptor node, Consumer<P2PMessage> messageHandler) {}

    @Override
    public void leave(String nodeId) {}

    @Override
    public void sendTo(String targetNodeId, P2PMessage message) {}

    @Override
    public void broadcast(P2PMessage message, Predicate<NodeDescriptor> targetFilter) {}

    @Override
    public Set<NodeDescriptor> peers() {
      return peers;
    }
  }

  private static final class ScriptedNetwork implements P2PNetwork {
    private final List<Set<NodeDescriptor>> snapshots;
    private final AtomicInteger index = new AtomicInteger();

    private ScriptedNetwork(List<Set<NodeDescriptor>> snapshots) {
      this.snapshots = new ArrayList<>(snapshots);
    }

    @Override
    public void join(NodeDescriptor node, Consumer<P2PMessage> messageHandler) {}

    @Override
    public void leave(String nodeId) {}

    @Override
    public void sendTo(String targetNodeId, P2PMessage message) {}

    @Override
    public void broadcast(P2PMessage message, Predicate<NodeDescriptor> targetFilter) {}

    @Override
    public Set<NodeDescriptor> peers() {
      int current = Math.min(index.getAndIncrement(), snapshots.size() - 1);
      return snapshots.get(current);
    }
  }
}

