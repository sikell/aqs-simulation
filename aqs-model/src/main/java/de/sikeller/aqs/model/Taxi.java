package de.sikeller.aqs.model;

import java.util.Set;

public interface Taxi extends Entity {
  OrderNode getTargetOrderNode();

  int getCurrentCapacity();

  boolean hasCapacity();

  boolean isEmpty();

  String getName();

  int getCapacity();

  TargetList getTargets();

  Set<Client> getContainedPassengers();

  Set<Client> getPlannedPassengers();

  double getTravelDistance();
}
