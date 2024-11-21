package de.sikeller.aqs.runner.stats;

import java.util.Collection;
import java.util.function.BiFunction;
import java.util.function.Function;

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

  public Result<Double> collectDouble(Collection<T> collection, Function<T, Double> mapper) {
    return collect(
        collection, mapper, Double.MAX_VALUE, 0D, Double::sum, (sum, count) -> sum / count);
  }

  public Result<Long> collectLong(Collection<T> collection, Function<T, Long> mapper) {
    return collect(
        collection, mapper, Long.MAX_VALUE, 0L, Long::sum, (sum, count) -> 1.0 * sum / count);
  }
}
