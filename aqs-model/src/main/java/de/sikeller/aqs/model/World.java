package de.sikeller.aqs.model;

import lombok.Builder;
import lombok.Data;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Data
@Builder
public class World {
  private final int maxX;
  private final int maxY;
  private final Random random = new Random(1L);
  @Builder.Default private final Set<Taxi> taxis = new HashSet<>();
  @Builder.Default private final Set<Client> clients = new HashSet<>();
  @Builder.Default private long currentTime = 0;

  @Builder.Default
  private Function<World, Boolean> isFinished =
      world -> world.getClients().stream().allMatch(Client::isFinished);

  public boolean isFinished() {
    return isFinished.apply(this);
  }

  public World snapshot() {
    return World.builder()
        .maxX(maxX)
        .maxY(maxY)
        .taxis(taxis.stream().map(Taxi::snapshot).collect(Collectors.toSet()))
        .clients(clients.stream().map(Client::snapshot).collect(Collectors.toSet()))
        .currentTime(currentTime)
        .isFinished(isFinished)
        .build();
  }
}
