package de.sikeller.aqs.model;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;
import lombok.Builder;

@Builder
public class Order {
  @Builder.Default private final List<Position> path = new LinkedList<>();

  public static Order of(Position... targets) {
    return Order.builder().path(Arrays.asList(targets)).build();
  }

  public Order snapshot() {
    return Order.builder().path(new LinkedList<>(path)).build();
  }

  public void removeIf(Predicate<Position> filter) {
    path.removeIf(filter);
  }

  public boolean isEmpty() {
    return path.isEmpty();
  }

  public Stream<Position> stream() {
    return path.stream();
  }

  public int size() {
    return path.size();
  }

  public Position get(int i) {
    return path.get(i);
  }
}
