package de.sikeller.aqs.simulation.stats;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class CollectorMinMaxAverageTest {

  @Test
  void testCollectDouble() {
    var collector = new CollectorMinMaxAverage<TestData>();
    var data = List.of(new TestData(1.0, 10L), new TestData(2.0, 20L), new TestData(3.0, 30L));

    var result = collector.collectDouble(data, TestData::doubleValue);

    assertEquals(1.0, result.min());
    assertEquals(3.0, result.max());
    assertEquals(2.0, result.avg());
    assertEquals(6.0, result.sum());
    assertEquals(3, result.count());
  }

  @Test
  void testCollectLong() {
    var collector = new CollectorMinMaxAverage<TestData>();
    var data = List.of(new TestData(1.0, 10L), new TestData(2.0, 20L), new TestData(3.0, 30L));

    var result = collector.collectLong(data, TestData::longValue);

    assertEquals(10L, result.min());
    assertEquals(30L, result.max());
    assertEquals(20.0, result.avg());
    assertEquals(60L, result.sum());
    assertEquals(3, result.count());
  }

  @Test
  void testEmptyCollection() {
    var collector = new CollectorMinMaxAverage<TestData>();
    var data = List.<TestData>of();

    var doubleResult = collector.collectDouble(data, TestData::doubleValue);
    assertEquals(0.0, doubleResult.min());
    assertEquals(0.0, doubleResult.max());
    assertEquals(0.0, doubleResult.avg());
    assertEquals(0.0, doubleResult.sum());
    assertEquals(0, doubleResult.count());

    var longResult = collector.collectLong(data, TestData::longValue);
    assertEquals(0L, longResult.min());
    assertEquals(0L, longResult.max());
    assertEquals(0.0, longResult.avg());
    assertEquals(0L, longResult.sum());
    assertEquals(0, longResult.count());
  }

  private record TestData(double doubleValue, long longValue) {}
}
