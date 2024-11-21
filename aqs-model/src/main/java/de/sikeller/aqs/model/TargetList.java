package de.sikeller.aqs.model;

import lombok.Builder;

import java.util.LinkedList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

@Builder
public class TargetList {
  @Builder.Default private final List<Order> orders = new LinkedList<>();
  @Builder.Default private final List<Position> flattenedTargets = new LinkedList<>();

  public static Function<List<Order>, List<Position>> defaultFlattenOrder =
      lists -> lists.stream().flatMap(Order::stream).toList();

  public void addOrder(Order order, Function<List<Order>, List<Position>> flattenFunction) {
    orders.add(order.snapshot());
    flattenedTargets.clear();
    flattenedTargets.addAll(flattenFunction.apply(orders));
  }

  public Position getAnRemoveFirst() {
    Position position = flattenedTargets.removeFirst();
    orders.forEach(o -> o.removeIf(e -> e.equals(position)));
    orders.removeIf(Order::isEmpty);
    return position;
  }

  public TargetList snapshot() {
    return TargetList.builder()
        .orders(
            orders.stream().map(Order::snapshot).collect(Collectors.toCollection(LinkedList::new)))
        .flattenedTargets(new LinkedList<>(flattenedTargets))
        .build();
  }

  public List<Position> toList() {
    return new LinkedList<>(flattenedTargets);
  }

  public boolean isEmpty() {
    return flattenedTargets.isEmpty();
  }

  public Position getFirst() {
    return flattenedTargets.getFirst();
  }
}
