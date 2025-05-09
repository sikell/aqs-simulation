package de.sikeller.aqs.model;

import java.util.Collection;
import java.util.Set;

public interface World extends WorldMutator {
  Set<Client> getSpawnedClients();

  Collection<Client> getClientsByModes(Set<ClientMode> modes, boolean onlySpawned);

  Collection<Client> getClientsByMode(ClientMode mode, boolean onlySpawned);

  Set<Client> getFinishedClients();

  int getSpawnProgress();

  int getFinishedProgress();

  boolean isFinished();

  int getMaxX();

  int getMaxY();

  Set<Taxi> getTaxis();

  Collection<Client> getClients();

  long getCurrentTime();

  WorldMutator mutate();
}
