package de.sikeller.aqs.model;

import de.sikeller.aqs.model.events.EventClientEntersTaxi;
import de.sikeller.aqs.model.events.EventClientLeaveTaxi;
import de.sikeller.aqs.model.events.EventDispatcher;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

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

  @Override
  public void updatePosition(Position position, long currentTime) {
    log.trace("Taxi {} update position: {}", name, position);
    this.travelDistance += this.position.distance(position);
    this.position = position;
    this.lastUpdate = currentTime;
    checkTargetReached(position);
    checkClientEntering(position, currentTime);
    checkClientLeaving(position, currentTime);
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
  public boolean isMoving() {
    return !targets.isEmpty() && !position.equals(getTarget());
  }

  public boolean hasCapacity() {
    return containedPassengers.size() + plannedPassengers.size() < capacity;
  }
}
