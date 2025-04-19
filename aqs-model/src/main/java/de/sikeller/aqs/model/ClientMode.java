package de.sikeller.aqs.model;

/**
 * An enumeration for the states a client can be in during the simulation.
 *
 * <p>When a client is created, it is initially in the WAITING state. In this state, the client
 * waits for being picked up. A WAITING client is PLANNED to be picked up. When the client is picked
 * up, it is moved to the MOVING state. In this state, the client is moved towards its target. When
 * the client has reached its target, it is moved to the FINISHED state and does not participate in
 * the simulation anymore.
 */
public enum ClientMode {
  /**
   * Client waits for being picked up, initial state... if nothing happens, the clients walk slowly
   * towards target until the state changes to any other state
   */
  WAITING,
  /** Client is planned to be picked up (but not yet picked up). */
  PLANNED,
  /** Client is moving (e.g. in a taxi) towards the target. */
  MOVING,
  /** Client has reached the target and does not participate in the simulation anymore. */
  FINISHED
}
