package de.sikeller.aqs.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.sikeller.aqs.model.TickDataPoint;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class SimulationRunnerTickDataBucketTest {

  @Test
  void aggregatesTicksBeforeStoringPoints() {
    var bucket = new SimulationRunner.TickDataBucket(2);
    var points = new ArrayList<TickDataPoint>();

    bucket.collect(new TickDataPoint(1, 10, 3, 1, 4, 1, 0), points::add);
    bucket.collect(new TickDataPoint(2, 30, 5, 2, 6, 1, 1), points::add);
    bucket.collect(new TickDataPoint(3, 50, 7, 3, 8, 1, 1), points::add);
    bucket.flush(points::add);

    assertEquals(2, points.size());
    assertEquals(new TickDataPoint(0, 10, 3, 1, 4, 1, 0), points.get(0));
    assertEquals(new TickDataPoint(2, 40, 6, 5, 14, 2, 2), points.get(1));
  }
}
