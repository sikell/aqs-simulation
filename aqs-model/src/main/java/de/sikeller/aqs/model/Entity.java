package de.sikeller.aqs.model;

/**
 * An entity is an object that can be moved around in the world, such as a client or a taxi.
 *
 * <p>Entities are identified by their name, have a position, a target, and a current speed.
 * Entities also keep track of the last time they were updated.
 *
 * <p>Entities can be moved around in the world by calling the {@link #updatePosition(Position,
 * long)} method. This method will update the position and last update time of the entity. It will
 * also check if the entity has reached its target and call the appropriate event handler methods.
 *
 * <p>Entities can also be asked if they are moving by calling the {@link #isMoving()} method. This
 * method will return true if the entity is moving towards its target and false if it is not.
 */
public interface Entity {
  /**
   * Returns the current position of the entity in the world.
   *
   * @return the current {@link Position} of the entity
   */
  Position getPosition();

  /**
   * Returns the target position of the entity in the world.
   *
   * @return the target {@link Position} of the entity
   */
  Position getTarget();

  /**
   * Returns the last time the entity was updated, in milliseconds since the epoch.
   *
   * @return the last time the entity was updated, in milliseconds since the epoch
   */
  long getLastUpdate();

  /**
   * Returns true if the entity is currently moving towards its target, false otherwise.
   *
   * <p>This method is used to determine if the entity is currently moving towards its target. If
   * the entity is moving, this method will return true. If the entity is not moving, this method
   * will return false.
   *
   * @return true if the entity is moving, false otherwise
   */
  boolean isMoving();

  /**
   * Returns the current speed of the entity, in units of distance per unit of time.
   *
   * <p>This method is used to determine the current speed of the entity. The speed is given in
   * units of distance per unit of time.
   *
   * @return the current speed of the entity
   */
  double getCurrentSpeed();

  /**
   * Updates the position of the entity.
   *
   * <p>This method should be called by the simulator to update the position of the entity. It will
   * update the position of the entity and check if the entity has reached its target.
   *
   * @param position the new position of the entity
   * @param currentTime the current time, in milliseconds since the epoch
   */
  void updatePosition(Position position, long currentTime);
}
