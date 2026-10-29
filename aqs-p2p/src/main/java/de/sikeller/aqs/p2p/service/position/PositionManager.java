package de.sikeller.aqs.p2p.service.position;

import de.sikeller.aqs.model.Position;
import java.util.Map;

/**
 * Position-related responsibilities extracted from node service. Implementations must be
 * thread-safe.
 */
public interface PositionManager {
  /**
   * Update the position for a node.
   *
   * @return {@code true} if the position actually changed (moved or first seen), {@code false} if
   *     the position is identical to the previously stored one.
   */
  boolean updatePosition(String nodeId, int x, int y, long tick);

  /** Snapshot for external callers: id -> [x, y] */
  Map<String, Position> snapshot();

  /** Snapshot of raw Position objects for internal algorithms. */
  Map<String, Position> snapshotPositions();

  /** Current revision counter that clients can use for caching decisions. */
  long currentRevision();

  /** Returns the most recent tick observed across positions (or 0 if none) */
  long currentMaxTick();
}
