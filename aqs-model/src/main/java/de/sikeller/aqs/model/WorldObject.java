package de.sikeller.aqs.model;

import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
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
  @Builder.Default private final Collection<Taxi> taxis = new ArrayList<>();
  @Builder.Default private final Collection<TaxiEntity> taxiEntities = new ArrayList<>();
  @Builder.Default private final Collection<Client> clients = new ArrayList<>();
  @Builder.Default private final Collection<ClientEntity> clientEntities = new ArrayList<>();
  @Builder.Default private long currentTime = 0;

  @Builder.Default
  private Function<WorldObject, Boolean> isFinished =
      world -> world.clients.stream().allMatch(Client::isFinished);

  @Override
  public Collection<Client> getClients() {
    try {
      lock.readLock().lock();
      return new ArrayList<>(clients);
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public Set<Taxi> getTaxis() {
    try {
      lock.readLock().lock();
      return new HashSet<>(taxis);
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public Set<Client> getSpawnedClients() {
    try {
      lock.readLock().lock();
      return clients.stream()
          .filter(client -> client.isSpawned(currentTime))
          .collect(Collectors.toSet());
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public Collection<Client> getClientsByModes(Set<ClientMode> modes, boolean onlySpawned) {
    try {
      lock.readLock().lock();
      Collection<Client> result = new ArrayList<>();
      // for loop to improve performance for large client collections and use ArrayList as result
      for (Client client : clients) {
        if ((!onlySpawned || client.isSpawned(currentTime)) && modes.contains(client.getMode())) {
          result.add(client);
        }
      }
      return result;
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public Collection<Client> getClientsByMode(ClientMode mode, boolean onlySpawned) {
    try {
      lock.readLock().lock();
      Collection<Client> result = new ArrayList<>();
      // for loop to improve performance for large client collections and use ArrayList as result
      for (Client client : clients) {
        if ((!onlySpawned || client.isSpawned(currentTime)) && mode.equals(client.getMode())) {
          result.add(client);
        }
      }
      return result;
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public Set<Client> getFinishedClients() {
    try {
      lock.readLock().lock();
      return clients.stream().filter(Client::isFinished).collect(Collectors.toSet());
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public int getSpawnProgress() {
    try {
      lock.readLock().lock();
      long spawnedClients =
          clients.stream().filter(client -> client.isSpawned(currentTime)).count();
      return Math.round(1.0f * spawnedClients / clients.size() * 100);
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public int getFinishedProgress() {
    try {
      lock.readLock().lock();
      long finishedClients = clients.stream().filter(Client::isFinished).count();
      return Math.round(1.0f * finishedClients / clients.size() * 100);
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public boolean isFinished() {
    try {
      lock.readLock().lock();
      return isFinished.apply(this);
    } finally {
      lock.readLock().unlock();
    }
  }

  public World snapshot() {
    try {
      lock.readLock().lock();

      Collection<Client> clients = new ArrayList<>();
      Collection<ClientEntity> clientEntities = new ArrayList<>();
      Map<String, ClientEntity> clientMap = new HashMap<>();
      for (ClientEntity clientEntity : this.clientEntities) {
        var clientSnapshot = clientEntity.snapshot();
        clients.add(clientSnapshot);
        clientEntities.add(clientSnapshot);
        clientMap.put(clientEntity.getName(), clientSnapshot);
      }

      Collection<Taxi> taxis = new ArrayList<>();
      Collection<TaxiEntity> taxiEntities = new ArrayList<>();
      for (TaxiEntity taxiEntity : this.taxiEntities) {
        var taxiSnapshot = taxiEntity.snapshot(clientMap);
        taxis.add(taxiSnapshot);
        taxiEntities.add(taxiSnapshot);
      }

      return WorldObject.builder()
          .maxX(maxX)
          .maxY(maxY)
          .taxis(taxis)
          .taxiEntities(taxiEntities)
          .clients(clients)
          .clientEntities(clientEntities)
          .currentTime(currentTime)
          .isFinished(isFinished)
          .build();
    } finally {
      lock.readLock().unlock();
    }
  }

  public void reset() {
    try {
      lock.writeLock().lock();
      this.taxis.clear();
      this.taxiEntities.clear();
      this.clients.clear();
      this.clientEntities.clear();
      currentTime = 0;
    } finally {
      lock.writeLock().unlock();
    }
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
    try {
      lock.readLock().lock();
      return taxiEntities.stream().filter(t -> t.isSame(taxi)).findFirst().orElseThrow();
    } finally {
      lock.readLock().unlock();
    }
  }

  private ClientEntity findClientEntity(Client client) {
    try {
      lock.readLock().lock();
      return clientEntities.stream().filter(c -> c.isSame(client)).findFirst().orElseThrow();
    } finally {
      lock.readLock().unlock();
    }
  }

  public void addClient(
      String name, int spawnTime, Position position, Position target, Integer clientSpeed) {
    try {
      lock.writeLock().lock();
      ClientEntity clientEntity =
          ClientEntity.builder()
              .name(name)
              .spawnTime(spawnTime)
              .position(position)
              .target(target)
              .currentSpeed(clientSpeed)
              .build();
      this.clients.add(clientEntity);
      this.clientEntities.add(clientEntity);
    } finally {
      lock.writeLock().unlock();
    }
  }

  public void addTaxi(
      String name, Integer taxiSeatCount, Position taxiPosition, Integer taxiSpeed) {
    try {
      lock.writeLock().lock();
      TaxiEntity taxiEntity =
          TaxiEntity.builder()
              .name(name)
              .capacity(taxiSeatCount)
              .position(taxiPosition)
              .currentSpeed(taxiSpeed)
              .build();
      this.taxis.add(taxiEntity);
      this.taxiEntities.add(taxiEntity);
    } finally {
      lock.writeLock().unlock();
    }
  }
}
