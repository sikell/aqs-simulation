package de.sikeller.aqs.simulation.stats;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects objects in a list over time.
 *
 * @param <T> the objects to be collected
 */
public class CollectorTimeSeries {
  public static class Collector<N> {
    private final ArrayList<N> series;

    public Collector(int expectedSize) {
      this.series = new ArrayList<>(expectedSize);
    }

    public void collect(N value) {
      series.add(value);
    }

    public List<N> result() {
      return new ArrayList<>(series);
    }
  }

  public static <T> Collector<T> newCollector(int expectedSize) {
    return new Collector<>(expectedSize);
  }

  public static <T> Collector<T> newCollector() {
    return new Collector<>(16);
  }
}
