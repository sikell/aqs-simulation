package de.sikeller.aqs.model;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.Getter;

@Builder
public class Order {
  @Getter private final Client client;
  @Getter private final long timestamp;
  @Builder.Default private final List<Position> path = new LinkedList<>();

  public static Order of(Client client, long timestamp) {
    return Order.builder()
        .client(client)
        .path(Arrays.asList(client.getPosition(), client.getTarget()))
        .timestamp(timestamp)
        .build();
  }

  public Order snapshot() {
    return Order.builder().client(client).path(new LinkedList<>(path)).timestamp(timestamp).build();
  }

  public void removeIf(Predicate<OrderNode> filter) {
    path.removeIf(position -> filter.test(new OrderNode(client, position)));
  }

  public boolean isEmpty() {
    return path.isEmpty();
  }

  public Stream<OrderNode> stream() {
    return path.stream().map(p -> new OrderNode(client, p));
  }

  public int size() {
    return path.size();
  }

  public OrderNode get(int i) {
    return new OrderNode(client, path.get(i));
  }
}
