package de.sikeller.aqs.p2p.service.position;

import de.sikeller.aqs.p2p.service.config.P2PConfig;
import de.sikeller.aqs.p2p.util.P2PGeoUtils;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Default PositionManager: concurrent map and a revision counter with throttling.
 * Uses {@link P2PConfig} for tunables so tests can override behaviour.
 */
public class PositionManagerImpl implements PositionManager {
  private final ConcurrentHashMap<String, Position> positions = new ConcurrentHashMap<>();
  private final AtomicLong revision = new AtomicLong();
  private final AtomicLong maxTick = new AtomicLong();
  private final P2PConfig config;

  public PositionManagerImpl(P2PConfig config) {
    this.config = Objects.requireNonNull(config, "config");
  }

  @Override
  public void updatePosition(String nodeId, int x, int y, long tick) {
    if (nodeId == null || nodeId.isBlank()) return;
    Position prev = positions.get(nodeId);
    Position next = new Position(x, y, tick);
    positions.put(nodeId, next);
    maybeBumpRevision(prev, next);
    maxTick.updateAndGet(cur -> Math.max(cur, tick));
  }

  @Override
  public Map<String, int[]> snapshot() {
    var map = new java.util.LinkedHashMap<String, int[]>();
    long ttl = Math.max(1L, config.positionTtlTicks());
    long now = maxTick.get();
    positions.forEach((id, pos) -> {
      if (pos != null && now - pos.tick() <= ttl) {
        map.put(id, new int[] {pos.x(), pos.y()});
      }
    });
    return map;
  }

  @Override
  public Map<String, Position> snapshotPositions() {
    return new HashMap<>(positions);
  }

  @Override
  public long currentRevision() {
    return revision.get();
  }

  @Override
  public long currentMaxTick() {
    return maxTick.get();
  }

  private void maybeBumpRevision(Position prev, Position next) {
    if (prev == null || next == null) {
      revision.incrementAndGet();
      return;
    }
    long throttleTicks = Math.max(1L, config.positionRevisionThrottleTicks());
    int minMove = Math.max(0, config.positionRevisionMinMoveMeters());
    long tickDelta = next.tick() - prev.tick();
    double dist = P2PGeoUtils.distance(prev.x(), prev.y(), next.x(), next.y());
    if (tickDelta >= throttleTicks) {
      revision.incrementAndGet();
      return;
    }
    if (dist >= minMove) {
      revision.incrementAndGet();
    }
  }
}

