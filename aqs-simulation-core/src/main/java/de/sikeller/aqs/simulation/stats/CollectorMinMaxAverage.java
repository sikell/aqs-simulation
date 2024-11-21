package de.sikeller.aqs.simulation.stats;

import java.util.Collection;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * A collector class that calculates the minimum, maximum, average, sum, and count of elements in a
 * given collection by applying a specified mapping function to each element.
 *
 * @param <T> the type of elements in the collection
 */
public class CollectorMinMaxAverage<T> {
  public record Result<T>(T min, T max, double avg, T sum, int count) {}

  private <N extends Number & Comparable<N>> Result<N> collect(
      Collection<T> collection,
      Function<T, N> mapper,
      N maxValue,
      N minValue,
      BiFunction<N, N, N> sumCalculator,
      BiFunction<N, Integer, Double> avgCalculator) {
    N min = maxValue;
    N max = minValue;
    N sum = minValue;
    int count = 0;

    for (T entry : collection) {
      var value = mapper.apply(entry);
      if (min.compareTo(value) > 0) {
        min = value;
      }
      if (max.compareTo(value) < 0) {
        max = value;
      }
      sum = sumCalculator.apply(sum, value);
      count++;
    }

    return new Result<>(min, max, avgCalculator.apply(sum, count), sum, count);
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
    return collect(
        collection, mapper, Double.MAX_VALUE, 0D, Double::sum, (sum, count) -> sum / count);
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
    return collect(
        collection, mapper, Long.MAX_VALUE, 0L, Long::sum, (sum, count) -> 1.0 * sum / count);
  }
}
