package de.sikeller.aqs.taxi.algorithm.distributed.rqs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.sikeller.aqs.model.*;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class SimulatedRangeQuerySystemSpatialIndexTest {

  @Test
  void indexedPointRangeMatchesBruteForceAcrossRandomQueries() {
    SimulatedRangeQuerySystem rqs = new SimulatedRangeQuerySystem();
    rqs.setParameters(Map.of("p2pRqsGridCellSize", 750));

    TestWorld world = TestWorld.withRandomTaxis();
    Random random = new Random(7L);

    for (int i = 0; i < 200; i++) {
      Position center =
          new Position(
              random.nextInt(world.getSize().getMaxX()), random.nextInt(world.getSize().getMaxY()));
      double radius = 200 + random.nextInt(4_000);

      Set<String> indexed =
          rqs.findTaxisInRange(world, center, center, radius).stream()
              .map(Taxi::getName)
              .collect(Collectors.toSet());
      Set<String> bruteForce = world.bruteForcePointRange(center, radius);

      assertEquals(bruteForce, indexed, "Mismatch for center=" + center + " radius=" + radius);
      world.advanceTick();
    }
  }

  private static final class TestWorld implements World {
    private final Set<Taxi> taxis;
    private final int maxX;
    private final int maxY;
    private long currentTime;

    private TestWorld(Set<Taxi> taxis, int maxX, int maxY) {
      this.taxis = taxis;
      this.maxX = maxX;
      this.maxY = maxY;
      this.currentTime = 0;
    }

    static TestWorld withRandomTaxis() {
      int taxiCount = 1_000;
      int maxX = 20_000;
      int maxY = 20_000;
      Random random = new Random(42L);
      Set<Taxi> taxis = new HashSet<>();
      for (int i = 0; i < taxiCount; i++) {
        taxis.add(new TestTaxi("t" + i, new Position(random.nextInt(maxX), random.nextInt(maxY))));
      }
      return new TestWorld(taxis, maxX, maxY);
    }

    void advanceTick() {
      currentTime++;
    }

    Set<String> bruteForcePointRange(Position center, double radius) {
      return taxis.stream()
          .filter(Taxi::hasCapacity)
          .filter(t -> t.getPosition().distance(center) <= radius)
          .map(Taxi::getName)
          .collect(Collectors.toSet());
    }

    @Override
    public Set<Client> getSpawnedClients() {
      return Set.of();
    }

    @Override
    public Collection<Client> getClientsByModes(Set<ClientMode> modes, boolean onlySpawned) {
      return Set.of();
    }

    @Override
    public Collection<Client> getClientsByMode(ClientMode mode, boolean onlySpawned) {
      return Set.of();
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
    public WorldSize getSize() {
      return new WorldSize(maxX, maxY);
    }

    @Override
    public Set<Taxi> getTaxis() {
      return taxis;
    }

    @Override
    public Collection<Client> getClients() {
      return Set.of();
    }

    @Override
    public long getCurrentTime() {
      return currentTime;
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

  private static final class TestTaxi implements Taxi {
    private final String name;
    private Position position;

    private TestTaxi(String name, Position position) {
      this.name = name;
      this.position = position;
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
      return true;
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public int getCapacity() {
      return 4;
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
    public void updatePosition(Position position, long currentTime) {
      this.position = position;
    }
  }
}
