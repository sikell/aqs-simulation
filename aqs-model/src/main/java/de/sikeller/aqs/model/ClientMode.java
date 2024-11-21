package de.sikeller.aqs.model;

public enum ClientMode {
  /** Client waits for being picked up, initial state... */
  WAITING,
  /** Client is moving (e.g. in a taxi or simply walking) towards the target. */
  MOVING,
  /** Client has reached the target and does not participate in the simulation anymore. */
  FINISHED
}
