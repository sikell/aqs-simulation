package de.sikeller.aqs.taxi.algorithm.collector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.sikeller.aqs.model.Client;
import de.sikeller.aqs.model.ClientMode;
import de.sikeller.aqs.model.OrderFlattenFunction;
import de.sikeller.aqs.model.OrderNode;
import de.sikeller.aqs.model.Position;
import de.sikeller.aqs.model.TargetList;
import de.sikeller.aqs.model.Taxi;
import de.sikeller.aqs.model.World;
import de.sikeller.aqs.model.WorldMutator;
import de.sikeller.aqs.p2p.api.NodeDescriptor;
import de.sikeller.aqs.p2p.api.P2PMessage;
import de.sikeller.aqs.p2p.api.P2PNetwork;
import de.sikeller.aqs.p2p.service.VehicleP2PService;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class LocalVehicleNodeManagerTest {

  @Test
  void ensureLocalVehicleNodesCreatesAndRemovesNodes() {
    RecordingNetwork network = new RecordingNetwork();
    Map<String, RecordingVehicleNode> created = new HashMap<>();
    LocalVehicleNodeManager manager =
        new LocalVehicleNodeManager(
            "vehicle-",
            (nodeId, n) -> {
              RecordingVehicleNode node = new RecordingVehicleNode(nodeId, n);
              created.put(nodeId, node);
              return node;
            });

    Map<String, VehicleP2PService> localNodes = new HashMap<>();
    Map<String, String> vehicleToTaxi = new HashMap<>();
    Map<String, String> taxiToVehicle = new HashMap<>();

    manager.ensureLocalVehicleNodes(
        new StubWorld(Set.of(new StubTaxi("t0", true, 0, 0), new StubTaxi("t1", true, 1, 1))),
        true,
        network,
        localNodes,
        vehicleToTaxi,
        taxiToVehicle);

    assertEquals(2, localNodes.size());
    assertTrue(created.get("vehicle-t0").started);
    assertTrue(created.get("vehicle-t1").started);

    manager.ensureLocalVehicleNodes(
        new StubWorld(Set.of(new StubTaxi("t0", true, 0, 0))),
        true,
        network,
        localNodes,
        vehicleToTaxi,
        taxiToVehicle);

    assertEquals(1, localNodes.size());
    assertFalse(localNodes.containsKey("t1"));
    assertTrue(created.get("vehicle-t1").stopped);
  }

  @Test
  void syncLocalVehicleStatesCreatesMissingNodesLazilyAndUpdatesState() {
    RecordingNetwork network = new RecordingNetwork();
    Map<String, RecordingVehicleNode> created = new HashMap<>();
    LocalVehicleNodeManager manager =
        new LocalVehicleNodeManager(
            "vehicle-",
            (nodeId, n) -> {
              RecordingVehicleNode node = new RecordingVehicleNode(nodeId, n);
              created.put(nodeId, node);
              return node;
            });

    Map<String, VehicleP2PService> localNodes = new HashMap<>();
    Map<String, String> vehicleToTaxi = new HashMap<>();
    Map<String, String> taxiToVehicle = new HashMap<>();

    manager.syncLocalVehicleStates(
        new StubWorld(Set.of(new StubTaxi("t0", true, 10, 11), new StubTaxi("t1", false, 20, 21))),
        true,
        network,
        7L,
        localNodes,
        vehicleToTaxi,
        taxiToVehicle);

    assertEquals(2, localNodes.size());
    assertEquals(7L, created.get("vehicle-t0").lastTick);
    assertEquals(10, created.get("vehicle-t0").lastX);
    assertEquals(11, created.get("vehicle-t0").lastY);
    assertTrue(created.get("vehicle-t0").lastAvailable);
    assertFalse(created.get("vehicle-t1").lastAvailable);
  }

  private static final class RecordingVehicleNode extends VehicleP2PService {
    boolean started;
    boolean stopped;
    boolean lastAvailable;
    int lastX;
    int lastY;
    long lastTick;

    private RecordingVehicleNode(String nodeId, P2PNetwork network) {
      super(nodeId, network);
    }

    @Override
    public synchronized void start() {
      started = true;
    }

    @Override
    public synchronized void stop() {
      stopped = true;
    }

    @Override
    public synchronized void setSimulationState(
        boolean available, int positionX, int positionY, long simulationTick) {
      lastAvailable = available;
      lastX = positionX;
      lastY = positionY;
      lastTick = simulationTick;
    }
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

  private static final class StubWorld implements World {
    private final Set<Taxi> taxis;

    private StubWorld(Set<Taxi> taxis) {
      this.taxis = taxis;
    }

    @Override
    public Set<Client> getSpawnedClients() {
      return Set.of();
    }

    @Override
    public Collection<Client> getClientsByModes(Set<ClientMode> modes, boolean onlySpawned) {
      return List.of();
    }

    @Override
    public Collection<Client> getClientsByMode(ClientMode mode, boolean onlySpawned) {
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
    public int getMaxX() {
      return 0;
    }

    @Override
    public int getMaxY() {
      return 0;
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
  }

  private static final class StubTaxi implements Taxi {
    private final String name;
    private final boolean empty;
    private final Position position;

    private StubTaxi(String name, boolean empty, int x, int y) {
      this.name = name;
      this.empty = empty;
      this.position = new Position(x, y);
    }

    @Override
    public OrderNode getTargetOrderNode() {
      return null;
    }

    @Override
    public int getCurrentCapacity() {
      return 0;
    }

    @Override
    public boolean hasCapacity() {
      return true;
    }

    @Override
    public boolean isEmpty() {
      return empty;
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
      return position;
    }

    @Override
    public Position getTarget() {
      return position;
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

