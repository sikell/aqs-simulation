package de.sikeller.aqs.model;

import de.sikeller.aqs.model.events.EventClientEntersTaxi;
import de.sikeller.aqs.model.events.EventClientLeaveTaxi;
import de.sikeller.aqs.model.events.EventDispatcher;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

/**
 * A taxi is an entity that can move around in the world and contain clients. Taxis are
 * characterized by their name, capacity, and current position.
 *
 * <p>Taxis also have a list of targets, which are positions that the taxi should visit in order.
 * The taxi will continuously move towards its target until it reaches it, at which point it will be
 * removed from the list. If the taxi is at the same position as its target, it is considered to
 * have reached its target.
 *
 * <p>Taxis also keep track of the clients that are currently contained in the taxi. Clients
 * automatically enter and leave the taxi if they are at the same position.
 */
@Slf4j
@Data
@Builder
@EqualsAndHashCode(of = "name")
public class Taxi implements Entity {
  private final String name;
  private final int capacity;
  private Position position;
  @Builder.Default private final TargetList targets = TargetList.builder().build();
  @Builder.Default private final Set<Client> containedPassengers = new HashSet<>();
  @Builder.Default private final Set<Client> plannedPassengers = new HashSet<>();
  @Builder.Default private long lastUpdate = 0;
  @Builder.Default private double currentSpeed = 1;
  @Builder.Default private double travelDistance = 0;

  public Taxi snapshot() {
    return Taxi.builder()
        .name(name)
        .capacity(capacity)
        .position(position)
        .targets(targets.snapshot())
        .containedPassengers(
            containedPassengers.stream().map(Client::snapshot).collect(Collectors.toSet()))
        .plannedPassengers(
            plannedPassengers.stream().map(Client::snapshot).collect(Collectors.toSet()))
        .build();
  }

  /**
   * Update the position of the taxi. This will check if the taxi has reached its target, if a
   * client is entering the taxi, and if a client is leaving the taxi.
   *
   * @param position the new position of the taxi
   * @param currentTime the current time
   */
  @Override
  public void updatePosition(Position position, long currentTime) {
    this.lastUpdate = currentTime;
    if (!isMoving()) return;
    this.travelDistance += this.position.distance(position);
    this.position = position;
    checkTargetReached(position);
    checkClientLeaving(position, currentTime);
    checkClientEntering(position, currentTime);
  }

  public void planClient(Client client) {
    client.setMode(ClientMode.MOVING);
    plannedPassengers.add(client);
  }

  public void forgetClient(Client client) {
    client.setMode(ClientMode.WAITING);
    plannedPassengers.remove(client);
  }

  public void addOrder(Order order, Function<List<Order>, List<Position>> flattenFunction) {
    targets.addOrder(order, flattenFunction);
  }

  public void planOrderPath(Function<List<Order>, List<Position>> flattenFunction) {
    targets.planOrders(flattenFunction);
  }

  private void checkTargetReached(Position position) {
    if (!targets.isEmpty() && position.equals(getTarget())) {
      var target = targets.getAnRemoveFirst();
      log.debug("Taxi {} reached a target: {}", name, target);
    }
  }

  private void checkClientLeaving(Position position, long currentTime) {
    containedPassengers.forEach(p -> p.updatePosition(position, currentTime));
    for (Client client : new HashSet<>(containedPassengers)) {
      if (client.isFinished()) {
        containedPassengers.remove(client);
        EventDispatcher.dispatch(new EventClientLeaveTaxi(currentTime, client, this));
        log.debug("Taxi {} passenger {} left", name, client.getName());
      }
    }
  }

  private void checkClientEntering(Position position, long currentTime) {
    for (Client client : new HashSet<>(plannedPassengers)) {
      if (client.getPosition().equals(position)) {
        if (containedPassengers.size() >= capacity) {
          log.warn(
              "Client {} should enter taxi {} but capacity reached. Client does not enter taxi!",
              client.getName(),
              this.getName());
          forgetClient(client);
          return;
        }
        plannedPassengers.remove(client);
        containedPassengers.add(client);
        EventDispatcher.dispatch(new EventClientEntersTaxi(currentTime, client, this));
        log.debug("Taxi {} passenger {} enters", name, client.getName());
      }
    }
  }

  @Override
  public Position getTarget() {
    return targets.isEmpty() ? position : targets.getFirst();
  }

  @Override
  public boolean isSpawned(long currentTime) {
    return true;
  }

  @Override
  public boolean isMoving() {
    return !targets.isEmpty() && !position.equals(getTarget());
  }

  public int getCurrentCapacity() {
    return capacity - (containedPassengers.size() + plannedPassengers.size());
  }

  public boolean hasCapacity() {
    return containedPassengers.size() + plannedPassengers.size() < capacity;
  }

  public boolean isEmpty() {
    return containedPassengers.isEmpty() && plannedPassengers.isEmpty();
  }
}
