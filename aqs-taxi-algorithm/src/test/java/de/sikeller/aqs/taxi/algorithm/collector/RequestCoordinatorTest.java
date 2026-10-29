package de.sikeller.aqs.taxi.algorithm.collector;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.sikeller.aqs.model.Client;
import de.sikeller.aqs.model.ClientEntity;
import de.sikeller.aqs.model.ClientMode;
import de.sikeller.aqs.model.OrderFlattenFunction;
import de.sikeller.aqs.model.OrderNode;
import de.sikeller.aqs.model.Position;
import de.sikeller.aqs.model.SpawnScenario;
import de.sikeller.aqs.model.TargetList;
import de.sikeller.aqs.model.Taxi;
import de.sikeller.aqs.model.World;
import de.sikeller.aqs.model.WorldMutator;
import de.sikeller.aqs.p2p.api.NodeDescriptor;
import de.sikeller.aqs.p2p.api.P2PMessage;
import de.sikeller.aqs.p2p.api.P2PNetwork;
import de.sikeller.aqs.p2p.service.ClientP2PService;
import de.sikeller.aqs.p2p.service.VehicleP2PService;
import de.sikeller.aqs.taxi.algorithm.distributed.rqs.RangeQuerySystem;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class RequestCoordinatorTest {

  @Test
  void embeddedSeedDeliveryOrderIsDeterministic() {
    RecordingNetwork network = new RecordingNetwork();
    ClientP2PService clientNode = new ClientP2PService("collector", network);
    TaxiCollectorRuntimeState runtimeState = new TaxiCollectorRuntimeState();
    List<String> deliveredTo = new ArrayList<>();
    Map<String, String> taxiToVehicle =
        Map.of("tB", "vehicle-b", "tA", "vehicle-a", "tC", "vehicle-c");
    Map<String, VehicleP2PService> vehicles =
        Map.of(
            "vehicle-a", new RecordingVehicleNode("vehicle-a", network, deliveredTo),
            "vehicle-b", new RecordingVehicleNode("vehicle-b", network, deliveredTo),
            "vehicle-c", new RecordingVehicleNode("vehicle-c", network, deliveredTo));
    Set<Taxi> taxis =
        new HashSet<>(
            List.of(
                new StubTaxi("tB", 10, 0),
                new StubTaxi("tC", 25, 0),
                new StubTaxi("tA", 10, 0)));
    RequestCoordinator coordinator =
        new RequestCoordinator(
            () -> clientNode,
            () ->
                Map.of(
                    "p2pEmbeddedSimulation", 1,
                    "p2pFixedSearchRadius", 100,
                    "p2pRequestForwardHops", 0),
            () -> 1L,
            runtimeState,
            new FixedRangeQuerySystem(taxis),
            taxiToVehicle,
            (vehicle, client) -> {},
            event -> {},
            vehicles::get);
    Client client =
        ClientEntity.builder()
            .name("client-1")
            .mode(ClientMode.WAITING)
            .position(new Position(0, 0))
            .target(new Position(100, 100))
            .build();

    coordinator.publishNewRequests(new StubWorld(taxis), List.of(client));

    assertEquals(List.of("vehicle-a", "vehicle-b", "vehicle-c"), deliveredTo);
  }

  private static final class RecordingVehicleNode extends VehicleP2PService {
    private final List<String> deliveredTo;

    private RecordingVehicleNode(String nodeId, P2PNetwork network, List<String> deliveredTo) {
      super(nodeId, network);
      this.deliveredTo = deliveredTo;
    }

    @Override
    public void deliverRideRequestDirect(
        String requestId, String originNodeId, Map<String, String> payload) {
      deliveredTo.add(descriptor().id());
    }
  }

  private record FixedRangeQuerySystem(Set<Taxi> taxis) implements RangeQuerySystem {
    @Override
    public Set<Taxi> findTaxisInRange(
        World world, Position clientStart, Position clientTarget, double searchRadius) {
      return taxis;
    }

    @Override
    public void setParameters(Map<String, Integer> parameters) {}
  }

  private static final class RecordingNetwork implements P2PNetwork {
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
      return Set.of();
    }
  }

  private record StubWorld(Set<Taxi> taxis) implements World {
    @Override
    public Set<Client> getSpawnedClients() {
      return Set.of();
    }

    @Override
    public Collection<Client> getClientsByModes(Set<de.sikeller.aqs.model.ClientMode> modes, boolean onlySpawned) {
      return List.of();
    }

    @Override
    public Collection<Client> getClientsByMode(de.sikeller.aqs.model.ClientMode mode, boolean onlySpawned) {
      return List.of();
    }

    @Override
    public Set<Client> getFinishedClients() {
      return Set.of();
    }

    @Override
    public int getSpawnProgress() {
      return 0;
    }

    @Override
    public int getFinishedProgress() {
      return 0;
    }

    @Override
    public boolean isFinished() {
      return false;
    }

    @Override
    public World.WorldSize getSize() {
      return World.size(1000, 1000);
    }

    @Override
    public Set<Taxi> getTaxis() {
      return taxis;
    }

    @Override
    public Collection<Client> getClients() {
      return List.of();
    }

    @Override
    public long getCurrentTime() {
      return 0;
    }

    @Override
    public WorldMutator mutate() {
      return this;
    }

    @Override
    public void planClientForTaxi(Taxi taxi, Client client, OrderFlattenFunction flattenFunction) {}

    @Override
    public void planOrderPath(Taxi taxi, OrderFlattenFunction flattenFunction) {}

    @Override
    public void clearTaxi(Taxi taxi) {}

    @Override
    public void setIdleTarget(Taxi taxi, Position target) {}
  }

  private record StubTaxi(String name, int x, int y) implements Taxi {
    @Override
    public OrderNode getTargetOrderNode() {
      return null;
    }

    @Override
    public int getCurrentCapacity() {
      return 1;
    }

    @Override
    public boolean hasCapacity() {
      return true;
    }

    @Override
    public boolean isEmpty() {
      return true;
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public int getCapacity() {
      return 1;
    }

    @Override
    public TargetList getTargets() {
      return TargetList.builder().build();
    }

    @Override
    public Set<Client> getContainedPassengers() {
      return Set.of();
    }

    @Override
    public Set<Client> getPlannedPassengers() {
      return Set.of();
    }

    @Override
    public double getTravelDistance() {
      return 0;
    }

    @Override
    public Position getPosition() {
      return new Position(x, y);
    }

    @Override
    public Position getTarget() {
      return getPosition();
    }

    @Override
    public long getLastUpdate() {
      return 0;
    }

    @Override
    public boolean isSpawned(long currentTime) {
      return true;
    }

    @Override
    public boolean isMoving() {
      return false;
    }

    @Override
    public double getCurrentSpeed() {
      return 0;
    }

    @Override
    public void updatePosition(Position position, long currentTime) {}
  }
}
