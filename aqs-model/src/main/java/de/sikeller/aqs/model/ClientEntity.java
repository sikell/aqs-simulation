package de.sikeller.aqs.model;

import de.sikeller.aqs.model.events.EventClientFinished;
import de.sikeller.aqs.model.events.EventDispatcher;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@Builder
@EqualsAndHashCode(of = "name")
public class ClientEntity implements Client {
  private final String name;
  @Builder.Default private long spawnTime = 0;
  @Builder.Default private ClientMode mode = ClientMode.WAITING;
  private Position position;
  private Position target;
  @Builder.Default private long lastUpdate = 0;
  @Builder.Default private double currentSpeed = 1;

  public ClientEntity snapshot() {
    return ClientEntity.builder()
        .name(name)
        .spawnTime(spawnTime)
        .mode(mode)
        .position(position)
        .target(target)
        .lastUpdate(lastUpdate)
        .currentSpeed(currentSpeed)
        .build();
  }

  @Override
  public boolean isSpawned(long currentTime) {
    return currentTime > spawnTime;
  }

  @Override
  public boolean isMoving() {
    return mode.equals(ClientMode.MOVING);
  }

  @Override
  public boolean isFinished() {
    return mode.equals(ClientMode.FINISHED);
  }

  public boolean isSame(Client client) {
    return name.equals(client.getName());
  }

  public void updatePosition(Position position, long currentTime) {
    this.lastUpdate = currentTime;
    if (!isMoving()) return;
    this.position = position;
    if (this.position.equals(target)) {
      EventDispatcher.dispatch(new EventClientFinished(currentTime, this));
      mode = ClientMode.FINISHED;
    }
  }
}
