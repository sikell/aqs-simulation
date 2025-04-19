package de.sikeller.aqs.model;

import java.util.HashSet;
import java.util.Set;
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
public class World implements WorldMutator {
  private final int maxX;
  private final int maxY;
  @Builder.Default private final Set<Taxi> taxis = new HashSet<>();
  @Builder.Default private final Set<ClientEntity> clients = new HashSet<>();
  @Builder.Default private long currentTime = 0;

  @Builder.Default
  private Function<World, Boolean> isFinished =
      world -> world.getClients().stream().allMatch(Client::isFinished);

  public Set<Client> getSpawnedClients() {
    return clients.stream()
        .filter(client -> client.isSpawned(currentTime))
        .collect(Collectors.toSet());
  }

  public Set<Client> getFinishedClients() {
    return clients.stream().filter(Client::isFinished).collect(Collectors.toSet());
  }

  public int getSpawnProgress() {
    return Math.round(1.0f * getSpawnedClients().size() / getClients().size() * 100);
  }

  public int getFinishedProgress() {
    return Math.round(1.0f * getFinishedClients().size() / getClients().size() * 100);
  }

  public boolean isFinished() {
    return isFinished.apply(this);
  }

  public World snapshot() {
    return World.builder()
        .maxX(maxX)
        .maxY(maxY)
        .taxis(taxis.stream().map(Taxi::snapshot).collect(Collectors.toSet()))
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
    var clientEntity = clients.stream().filter(c -> c.isSame(client)).findFirst().orElseThrow();
    taxi.planClient(clientEntity);
    taxi.addOrder(Order.of(clientEntity, currentTime), flattenFunction);
  }

  @Override
  public void planOrderPath(Taxi taxi, OrderFlattenFunction flattenFunction) {
    taxi.planOrderPath(flattenFunction);
  }
}
