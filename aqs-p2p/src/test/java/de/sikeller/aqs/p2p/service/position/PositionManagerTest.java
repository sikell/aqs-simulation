package de.sikeller.aqs.p2p.service.position;

import static org.junit.jupiter.api.Assertions.*;

import de.sikeller.aqs.model.Position;
import de.sikeller.aqs.p2p.service.config.P2PConfig;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PositionManagerTest {

  private static final P2PConfig TEST_CONFIG =
      new P2PConfig() {
        @Override
        public int overlayMinNeighbors() {
          return 1;
        }

        @Override
        public int overlayMaxNeighbors() {
          return Integer.MAX_VALUE;
        }

        @Override
        public double overlayMaxDistance() {
          return Double.MAX_VALUE;
        }

        @Override
        public String overlayShortcutStrategy() {
          return "kleinberg";
        }

        @Override
        public int overlayShortcuts() {
          return 1;
        }

        @Override
        public long positionTtlTicks() {
          return 5L;
        }

        @Override
        public long positionRevisionThrottleTicks() {
          return 3L;
        }

        @Override
        public int positionRevisionMinMoveMeters() {
          return 10;
        }
      };

  @Test
  void snapshotRespectsTtl() {
    PositionManagerImpl pm = new PositionManagerImpl(TEST_CONFIG);
    pm.updatePosition("v1", 0, 0, 10);
    pm.updatePosition("v2", 5, 5, 6);
    // maxTick should be 10
    Map<String, Position> snap = pm.snapshot();
    assertTrue(snap.containsKey("v1"));
    assertTrue(snap.containsKey("v2"));

    // advance tick beyond TTL for v2
    pm.updatePosition("v1", 0, 0, 20);
    Map<String, Position> snap2 = pm.snapshot();
    assertTrue(snap2.containsKey("v1"));
    // v2 tick 6 is now older than maxTick 20 by > TTL (14 > 5)
    assertFalse(snap2.containsKey("v2"));
  }

  @Test
  void revisionBumpsOnSignificantMoveOrTick() {
    PositionManagerImpl pm = new PositionManagerImpl(TEST_CONFIG);
    assertEquals(0L, pm.currentRevision());
    pm.updatePosition("v1", 0, 0, 1);
    long r1 = pm.currentRevision();
    assertTrue(r1 >= 1L);

    // small move within minMove and small tick delta -> no bump
    long before = pm.currentRevision();
    pm.updatePosition("v1", 1, 1, 2); // distance ~1.414 < 10, tickDelta=1 < throttle(3)
    assertEquals(before, pm.currentRevision());

    // big move -> bump
    pm.updatePosition("v1", 100, 100, 2);
    assertTrue(pm.currentRevision() > before);

    long revAfterMove = pm.currentRevision();
    // small move but tick delta exceeds throttle -> bump
    pm.updatePosition("v1", 100, 100, 10);
    assertTrue(pm.currentRevision() > revAfterMove);
  }
}
