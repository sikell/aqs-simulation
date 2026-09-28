package de.sikeller.aqs.p2p.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class P2PRunContextTest {

  @Test
  void communicationTimeIsAccumulatedAndDrained() {
    AtomicLong work = new AtomicLong();
    P2PRunContext.measureCommunication(
        () -> {
          for (int i = 0; i < 10_000; i++) {
            work.addAndGet(i);
          }
          P2PRunContext.measureCommunication(work::incrementAndGet);
        });

    assertTrue(P2PRunContext.drainCommunicationTimeNanos() > 0L);
    assertEquals(0L, P2PRunContext.drainCommunicationTimeNanos());
    P2PRunContext.clear();
  }
}
