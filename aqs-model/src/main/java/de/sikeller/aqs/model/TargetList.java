package de.sikeller.aqs.model;

import java.util.LinkedList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.Builder;

@Builder
public class TargetList {
  @Builder.Default private final List<Order> orders = new LinkedList<>();
  @Builder.Default private final List<Position> flattenedTargets = new LinkedList<>();

  /**
   * <code>
   * [[A, B], [a, b, c], [1, 2]] => [A, B, a, b, c, 1, 2]
   * </code>
   */
  public static Function<List<Order>, List<Position>> sequentialOrders =
      orders -> orders.stream().flatMap(Order::stream).toList();

  /**
   * <code>
   * [[A, B], [a, b, c], [1, 2]] => [A, a, 1, B, b, 2, c]
   * </code>
   */
  public static Function<List<Order>, List<Position>> mergeOrders =
      orders -> {
        List<Position> result = new LinkedList<>();
        int maxSize = orders.stream().mapToInt(Order::size).max().orElse(0);
        for (int i = 0; i < maxSize; i++) {
          for (Order innerList : orders) {
            if (i < innerList.size()) {
              result.add(innerList.get(i));
            }
          }
        }
        return result;
      };

  public void addOrder(Order order, Function<List<Order>, List<Position>> flattenFunction) {
    orders.add(order.snapshot());
    planOrders(flattenFunction);
  }

  public void planOrders(Function<List<Order>, List<Position>> flattenFunction) {
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
