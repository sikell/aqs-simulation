package de.sikeller.aqs.model;

import de.sikeller.aqs.model.events.EventClientEntersTaxi;
import de.sikeller.aqs.model.events.EventClientLeaveTaxi;
import de.sikeller.aqs.model.events.EventDispatcher;
import java.util.HashSet;
import java.util.Set;
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
public class TaxiEntity implements Taxi {
  private final String name;
  private final int capacity;
  private Position position;
  @Builder.Default private final TargetList targets = TargetList.builder().build();
  @Builder.Default private final Set<ClientEntity> containedPassengers = new HashSet<>();
  @Builder.Default private final Set<ClientEntity> plannedPassengers = new HashSet<>();
  @Builder.Default private long lastUpdate = 0;
  @Builder.Default private double currentSpeed = 1;
  @Builder.Default private double travelDistance = 0;
  private final ErrorHandler errorHandler = ErrorHandlerFactory.getInstance();

  public TaxiEntity snapshot() {
    return TaxiEntity.builder()
        .name(name)
        .capacity(capacity)
        .position(position)
        .targets(targets.snapshot())
        .containedPassengers(
            containedPassengers.stream().map(ClientEntity::snapshot).collect(Collectors.toSet()))
        .plannedPassengers(
            plannedPassengers.stream().map(ClientEntity::snapshot).collect(Collectors.toSet()))
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
    containedPassengers.forEach(p -> p.updatePosition(position, currentTime));
    OrderNode target = checkTargetReached(position);
    if (target != null) {
      checkClientLeaving(currentTime);
      checkClientEntering(target, currentTime);
    }
  }

  public void planClient(ClientEntity client) {
    plannedPassengers.add(client);
    client.setMode(ClientMode.PLANNED);
  }

  public void pickupClient(ClientEntity client) {
    if (!client.getPosition().equals(position)) {
      errorHandler.error("Try to pickup a client which is too far away :-(");
      return;
    }
    client.setMode(ClientMode.MOVING);
    plannedPassengers.remove(client);
    containedPassengers.add(client);
  }

  public void forgetClient(ClientEntity client) {
    if (!plannedPassengers.contains(client)) {
      errorHandler.error("Try to forget a client which is unknown to the given taxi!");
      return;
    }
    // todo cleanup targets
    client.setMode(ClientMode.WAITING);
    plannedPassengers.remove(client);
  }

  public void dropOffClient(ClientEntity client) {
    if (!containedPassengers.contains(client)) {
      errorHandler.error("Try to drop off a client which is not contained in the given taxi!");
      return;
    }
    // todo cleanup targets
    client.setMode(ClientMode.WAITING);
    containedPassengers.remove(client);
  }

  public void clearTaxi() {
    targets.clear();
    for (ClientEntity client : new HashSet<>(plannedPassengers)) {
      forgetClient(client);
    }
    for (ClientEntity client : new HashSet<>(containedPassengers)) {
      dropOffClient(client);
    }
  }

  public void addOrder(Order order, OrderFlattenFunction flattenFunction) {
    targets.addOrder(order, flattenFunction);
  }

  public void planOrderPath(OrderFlattenFunction flattenFunction) {
    targets.planOrders(flattenFunction);
  }

  private OrderNode checkTargetReached(Position position) {
    if (!targets.isEmpty() && position.equals(getTarget())) {
      var target = targets.getAnRemoveFirst();
      log.debug("Taxi {} reached a target: {}", name, target);
      return target;
    }
    return null;
  }

  private void checkClientLeaving(long currentTime) {
    for (ClientEntity client : new HashSet<>(containedPassengers)) {
      if (client.isFinished()) {
        containedPassengers.remove(client);
        EventDispatcher.dispatch(new EventClientLeaveTaxi(currentTime, client, this));
        log.debug("Taxi {} passenger {} left", name, client.getName());
      }
    }
  }

  private void checkClientEntering(OrderNode currentTarget, long currentTime) {
    if (currentTarget.getClient() == null) {
      errorHandler.error("Taxi reached a target but client in order node is empty!");
      return;
    }
    var client =
        plannedPassengers.stream()
            .filter(c -> c.isSame(currentTarget.getClient()))
            .findFirst()
            .orElse(null);
    if (client == null) {
      // client is not planned to be picked up... so ignore
      return;
    }
    if (client.getPosition().equals(currentTarget.getPosition())) {
      if (containedPassengers.size() >= capacity) {
        errorHandler.error(
            "Client %s should enter taxi %s but capacity reached. Client does not enter taxi!",
            client.getName(), this.getName());
        forgetClient(client);
        return;
      }
      pickupClient(client);
      EventDispatcher.dispatch(new EventClientEntersTaxi(currentTime, client, this));
      log.debug("Taxi {} passenger {} enters", name, client.getName());
    }
  }

  @Override
  public Position getTarget() {
    return targets.getFirst(position);
  }

  @Override
  public OrderNode getTargetOrderNode() {
    return targets.getFirst();
  }

  @Override
  public boolean isSpawned(long currentTime) {
    return true;
  }

  @Override
  public boolean isMoving() {
    return !targets.isEmpty();
  }

  @Override
  public int getCurrentCapacity() {
    return capacity - (containedPassengers.size() + plannedPassengers.size());
  }

  @Override
  public boolean hasCapacity() {
    return containedPassengers.size() + plannedPassengers.size() < capacity;
  }

  @Override
  public boolean isEmpty() {
    return containedPassengers.isEmpty() && plannedPassengers.isEmpty();
  }

  public boolean isSame(Taxi taxi) {
    return name.equals(taxi.getName());
  }
}
