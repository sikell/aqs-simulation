package de.sikeller.aqs.model;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

public class EntityUtils {
  public static double currentDistance(Entity e1, Entity e2) {
    return e1.getPosition().distance(e2.getPosition());
  }

  public static <T extends Entity> Tuple<T, Double> findNearestNeighbor(
      Entity entity, Collection<T> others) {
    return others.stream()
        .map(e -> new Tuple<>(e, currentDistance(e, entity)))
        .min(Comparator.comparingDouble(Tuple::v2))
        .orElse(null);
  }

  public static <T extends Position> List<Tuple<T, Double>> sortByNearest(
      Position position, Collection<T> others) {
    return others.stream()
        .map(e -> new Tuple<>(e, e.distance(position)))
        .sorted(Comparator.comparingDouble(Tuple::v2))
        .toList();
  }
}
