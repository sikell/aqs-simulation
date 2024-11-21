package de.sikeller.aqs.model;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
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
  @Builder.Default private final List<Position> targets = new LinkedList<>();
  @Builder.Default private final Set<Client> containedPassengers = new HashSet<>();
  @Builder.Default private final Set<Client> plannedPassengers = new HashSet<>();
  @Builder.Default private long lastUpdate = 0;
  @Builder.Default private double currentSpeed = 1;

  public Taxi snapshot() {
    return Taxi.builder()
        .name(name)
        .capacity(capacity)
        .position(position)
        .targets(new LinkedList<>(targets))
        .containedPassengers(
            containedPassengers.stream().map(Client::snapshot).collect(Collectors.toSet()))
        .plannedPassengers(
            plannedPassengers.stream().map(Client::snapshot).collect(Collectors.toSet()))
        .build();
  }

  @Override
  public void updatePosition(Position position, long currentTime) {
    log.trace("Taxi {} update position: {}", name, position);
    this.position = position;
    this.lastUpdate = currentTime;
    checkTargetReached(position);
    checkClientEntering(position);
    checkClientLeaving(position, currentTime);
  }

  private void checkTargetReached(Position position) {
    if (!targets.isEmpty() && position.equals(getTarget())) {
      var target = targets.removeFirst();
      log.info("Taxi {} reached a target: {}", name, target);
    }
  }

  private void checkClientLeaving(Position position, long currentTime) {
    containedPassengers.forEach(p -> p.updatePosition(position, currentTime));
    for (Client contained : new HashSet<>(containedPassengers)) {
      if (contained.isFinished()) {
        containedPassengers.remove(contained);
        log.info("Taxi {} passenger {} left", name, contained.getName());
      }
    }
  }

  private void checkClientEntering(Position position) {
    for (Client planned : new HashSet<>(plannedPassengers)) {
      if (planned.getPosition().equals(position)) {
        plannedPassengers.remove(planned);
        containedPassengers.add(planned);
        log.info("Taxi {} passenger {} enters", name, planned.getName());
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
