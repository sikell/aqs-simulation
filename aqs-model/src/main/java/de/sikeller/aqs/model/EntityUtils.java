package de.sikeller.aqs.model;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/** Utility class for working with {@link Entity}s in the simulation. */
public class EntityUtils {
  /**
   * Calculates the current distance between two entities.
   *
   * @param e1 the first entity
   * @param e2 the second entity
   * @return the Euclidean distance between the two entities
   */
  public static double currentDistance(Entity e1, Entity e2) {
    return e1.getPosition().distance(e2.getPosition());
  }

  /**
   * Finds the nearest neighbor of a given entity from a collection of other entities.
   *
   * @param position the position to which to find the nearest neighbor
   * @param others the collection of other entities to consider
   * @param <T> the type of the entities in the collection, which must extend Entity
   * @return a Tuple containing the nearest entity and the distance to it, or null if the collection
   *     is empty
   */
  public static <T extends Entity> Tuple<T, Double> findNearestNeighbor(
      Position position, Collection<T> others) {
    return others.stream()
        .map(e -> new Tuple<>(e, e.getPosition().distance(position)))
        .min(Comparator.comparingDouble(Tuple::v2))
        .orElse(null);
  }

  /**
   * Sorts a collection of {@link Entity}s by their distance to a given position and returns a list
   * of tuples, where each tuple contains the position and its distance to the given position.
   *
   * @param position the position to which to calculate the distances
   * @param others the collection of {@link Entity}s to sort
   * @param <T> the type of the positions in the collection, which must extend Position
   * @return a list of tuples, sorted by the distance to the given position
   */
  public static <T extends Entity> List<Tuple<T, Double>> sortByNearest(
      Position position, Collection<T> others) {
    return others.stream()
        .map(e -> new Tuple<>(e, e.getPosition().distance(position)))
        .sorted(Comparator.comparingDouble(Tuple::v2))
        .toList();
  }
}
