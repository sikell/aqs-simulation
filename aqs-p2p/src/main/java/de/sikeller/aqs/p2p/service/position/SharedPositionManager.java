package de.sikeller.aqs.p2p.service.position;

import de.sikeller.aqs.model.Position;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * PositionManager backed by a shared positions map. All embedded vehicle nodes share the same
 * instance. The map is swapped atomically once per tick (O(1)).
 */
public class SharedPositionManager implements PositionManager {
  private final AtomicReference<Map<String, Position>> sharedPositions =
      new AtomicReference<>(Map.of());
  private final AtomicLong revision = new AtomicLong();
  private final AtomicLong maxTick = new AtomicLong();

  /** Swap the shared positions map atomically. Called once per tick by the collector. */
  public void updateSharedPositions(Map<String, Position> positions, long tick) {
    if (positions != null) {
      sharedPositions.set(positions);
      revision.incrementAndGet();
      maxTick.updateAndGet(cur -> Math.max(cur, tick));
    }
  }

  @Override
  public boolean updatePosition(String nodeId, int x, int y, long tick) {
    maxTick.updateAndGet(cur -> Math.max(cur, tick));
    revision.incrementAndGet();
    return true;
  }

  @Override
  public Map<String, Position> snapshot() {
    return sharedPositions.get();
  }

  @Override
  public Map<String, Position> snapshotPositions() {
    return sharedPositions.get();
  }

  @Override
  public long currentRevision() {
    return revision.get();
  }

  @Override
  public long currentMaxTick() {
    return maxTick.get();
  }
}

