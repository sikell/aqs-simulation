package de.sikeller.aqs.model;

import java.util.Collection;
import java.util.Set;
import lombok.Value;

public interface World extends WorldMutator {
  Set<Client> getSpawnedClients();

  Collection<Client> getClientsByModes(Set<ClientMode> modes, boolean onlySpawned);

  Collection<Client> getClientsByMode(ClientMode mode, boolean onlySpawned);

  Set<Client> getFinishedClients();

  int getSpawnProgress();

  int getFinishedProgress();

  boolean isFinished();

  @Value
  class WorldSize {
    int maxX;
    int maxY;
  }

  static WorldSize size(int maxX, int maxY) {
    return new WorldSize(maxX, maxY);
  }

  WorldSize getSize();

  Set<Taxi> getTaxis();

  Collection<Client> getClients();

  long getCurrentTime();

  WorldMutator mutate();
}
