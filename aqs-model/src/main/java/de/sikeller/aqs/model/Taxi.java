package de.sikeller.aqs.model;

public interface Taxi extends Entity {
  OrderNode getTargetOrderNode();

  int getCurrentCapacity();

  boolean hasCapacity();

  boolean isEmpty();

  String getName();

  int getCapacity();

  TargetList getTargets();

  java.util.Set<ClientEntity> getContainedPassengers();

  java.util.Set<ClientEntity> getPlannedPassengers();

  double getTravelDistance();
}
