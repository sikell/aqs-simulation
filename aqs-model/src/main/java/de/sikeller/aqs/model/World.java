package de.sikeller.aqs.model;

import java.util.Set;

public interface World extends WorldMutator {
  Set<Client> getSpawnedClients();

  Set<Client> getClientsByModes(Set<ClientMode> modes, boolean onlySpawned);

  Set<Client> getClientsByMode(ClientMode mode, boolean onlySpawned);

  Set<Client> getFinishedClients();

  int getSpawnProgress();

  int getFinishedProgress();

  boolean isFinished();

  int getMaxX();

  int getMaxY();

  Set<Taxi> getTaxis();

  Set<Client> getClients();

  long getCurrentTime();

  WorldMutator mutate();
}
