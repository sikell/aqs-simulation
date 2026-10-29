package de.sikeller.aqs.p2p.service.util;

import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class AliasSamplerTest {
  @Test
  public void buildAndSample_tableProducesValidIndices() {
    double[] weights = new double[] {1.0, 2.0, 3.0};
    double total = 6.0;
    AliasSampler.Table table = AliasSampler.build(weights, total);
    assertNotNull(table);
    assertEquals(3, table.prob.length);
    assertEquals(3, table.alias.length);

    Random rnd = new Random(42L);
    for (int i = 0; i < 100; i++) {
      int idx = AliasSampler.sample(rnd, table);
      assertTrue(idx >= 0 && idx < weights.length, "sampled index in range");
    }
  }
}

