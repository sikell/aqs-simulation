package de.sikeller.aqs.simulation.stats;

import java.util.Collection;
import java.util.function.BiFunction;
import java.util.function.Function;
import lombok.AllArgsConstructor;

/**
 * A collector class that calculates the minimum, maximum, average, sum, and count of elements in a
 * given collection by applying a specified mapping function to each element.
 *
 * @param <T> the type of elements in the collection
 */
public class CollectorMinMaxAverage<T> {
  public record Result<T>(T min, T max, double avg, T sum, int count) {}

  @AllArgsConstructor
  public static class Collector<N extends Number & Comparable<N>> {
    private N min;
    private N max;
    private N sum;
    private int count;
    private BiFunction<N, N, N> sumCalculator;
    private BiFunction<N, Integer, Double> avgCalculator;

    public void collect(N value) {
      if (min.compareTo(value) > 0) {
        min = value;
      }
      if (max.compareTo(value) < 0) {
        max = value;
      }
      sum = sumCalculator.apply(sum, value);
      count++;
    }

    public Result<N> result() {
      return result(n -> n);
    }

    public Result<N> result(Function<N, N> mapper) {
      N mappedSum = mapper.apply(sum);
      return new Result<>(
          mapper.apply(min),
          mapper.apply(max),
          avgCalculator.apply(mappedSum, count),
          mappedSum,
          count);
    }
  }

  public static Collector<Double> doubleCollector() {
    return new Collector<>(Double.MAX_VALUE, 0D, 0D, 0, Double::sum, (sum, count) -> sum / count);
  }

  public static Collector<Long> longCollector() {
    return new Collector<>(Long.MAX_VALUE, 0L, 0L, 0, Long::sum, (sum, count) -> 1.0 * sum / count);
  }

  private <N extends Number & Comparable<N>> Result<N> collect(
      Collection<T> collection, Function<T, N> mapper, Collector<N> collector) {
    for (T entry : collection) {
      var value = mapper.apply(entry);
      collector.collect(value);
    }
    return collector.result();
  }

  /**
   * Collects statistics (minimum, maximum, average, sum, and count) from a collection of elements
   * by mapping each element to a double value using the provided mapper function.
   *
   * @param collection the collection of elements to process
   * @param mapper the function to map each element to a double value
   * @return a Result containing the calculated statistics
   */
  public Result<Double> collectDouble(Collection<T> collection, Function<T, Double> mapper) {
    return collect(collection, mapper, doubleCollector());
  }

  /**
   * Collects statistics (minimum, maximum, average, sum, and count) from a collection of elements
   * by mapping each element to a long value using the provided mapper function.
   *
   * @param collection the collection of elements to process
   * @param mapper the function to map each element to a long value
   * @return a Result containing the calculated statistics
   */
  public Result<Long> collectLong(Collection<T> collection, Function<T, Long> mapper) {
    return collect(collection, mapper, longCollector());
  }
}
