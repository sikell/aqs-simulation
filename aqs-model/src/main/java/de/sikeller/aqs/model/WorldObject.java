package de.sikeller.aqs.model;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.Data;

/**
 * The World class represents a simulation environment where taxis and clients are present. It
 * maintains the boundaries of the simulation, a set of taxis, a set of clients, and the current
 * time.
 *
 * <p>A world object is always a snapshot of the scenario at a specific point in time.
 */
@Data
@Builder
public class WorldObject implements World {
  private final int maxX;
  private final int maxY;
  private final ReadWriteLock lock = new ReentrantReadWriteLock();
  @Builder.Default private final Set<TaxiEntity> taxis = new HashSet<>();
  @Builder.Default private final Set<ClientEntity> clients = new HashSet<>();
  @Builder.Default private long currentTime = 0;

  @Builder.Default
  private Function<World, Boolean> isFinished =
      world -> world.getClients().stream().allMatch(Client::isFinished);

  @Override
  public Set<Client> getClients() {
    try {
      lock.readLock().lock();
      return clients.stream().map(c -> (Client) c).collect(Collectors.toSet());
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public Set<Taxi> getTaxis() {
    try {
      lock.readLock().lock();
      return taxis.stream().map(t -> (Taxi) t).collect(Collectors.toSet());
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public Set<Client> getSpawnedClients() {
    return clients.stream()
        .filter(client -> client.isSpawned(currentTime))
        .collect(Collectors.toSet());
  }

  @Override
  public Set<Client> getClientsByModes(Set<ClientMode> modes, boolean onlySpawned) {
    return (onlySpawned ? getSpawnedClients() : getClients())
        .stream().filter(client -> modes.contains(client.getMode())).collect(Collectors.toSet());
  }

  @Override
  public Set<Client> getFinishedClients() {
    return clients.stream().filter(Client::isFinished).collect(Collectors.toSet());
  }

  @Override
  public int getSpawnProgress() {
    return Math.round(1.0f * getSpawnedClients().size() / getClients().size() * 100);
  }

  @Override
  public int getFinishedProgress() {
    return Math.round(1.0f * getFinishedClients().size() / getClients().size() * 100);
  }

  @Override
  public boolean isFinished() {
    return isFinished.apply(this);
  }

  public World snapshot() {
    return WorldObject.builder()
        .maxX(maxX)
        .maxY(maxY)
        .taxis(taxis.stream().map(TaxiEntity::snapshot).collect(Collectors.toSet()))
        .clients(clients.stream().map(ClientEntity::snapshot).collect(Collectors.toSet()))
        .currentTime(currentTime)
        .isFinished(isFinished)
        .build();
  }

  public void reset() {
    this.taxis.clear();
    this.clients.clear();
    this.currentTime = 0;
  }

  public WorldMutator mutate() {
    return this;
  }

  @Override
  public void planClientForTaxi(Taxi taxi, Client client, OrderFlattenFunction flattenFunction) {
    var clientEntity = findClientEntity(client);
    var taxiEntity = findTaxiEntity(taxi);
    taxiEntity.planClient(clientEntity);
    taxiEntity.addOrder(Order.of(clientEntity, currentTime), flattenFunction);
  }

  @Override
  public void planOrderPath(Taxi taxi, OrderFlattenFunction flattenFunction) {
    var taxiEntity = findTaxiEntity(taxi);
    taxiEntity.planOrderPath(flattenFunction);
  }

  @Override
  public void clearTaxi(Taxi taxi) {
    try {
      lock.writeLock().lock();
      var taxiEntity = findTaxiEntity(taxi);
      taxiEntity.clearTaxi();
    } finally {
      lock.writeLock().unlock();
    }
  }

  private TaxiEntity findTaxiEntity(Taxi taxi) {
    return taxis.stream().filter(t -> t.isSame(taxi)).findFirst().orElseThrow();
  }

  private ClientEntity findClientEntity(Client client) {
    return clients.stream().filter(c -> c.isSame(client)).findFirst().orElseThrow();
  }

  public void addClient(
      String name, int spawnTime, Position position, Position target, Integer clientSpeed) {
    try {
      lock.writeLock().lock();
      this.clients.add(
          ClientEntity.builder()
              .name(name)
              .spawnTime(spawnTime)
              .position(position)
              .target(target)
              .currentSpeed(clientSpeed)
              .build());
    } finally {
      lock.writeLock().unlock();
    }
  }

  public void addTaxi(
      String name, Integer taxiSeatCount, Position taxiPosition, Integer taxiSpeed) {
    try {
      lock.writeLock().lock();
      this.taxis.add(
          TaxiEntity.builder()
              .name(name)
              .capacity(taxiSeatCount)
              .position(taxiPosition)
              .currentSpeed(taxiSpeed)
              .build());
    } finally {
      lock.writeLock().unlock();
    }
  }
}
