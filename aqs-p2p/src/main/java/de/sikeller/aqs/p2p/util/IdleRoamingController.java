package de.sikeller.aqs.p2p.util;

import de.sikeller.aqs.model.Position;
import de.sikeller.aqs.model.SpawnScenario;
import de.sikeller.aqs.p2p.api.P2PSystemProperties;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

/** Wrapper for a client position with TTL (time-to-live) in simulation ticks. */
class SeenClientPositionWithTtl {
  final int[] position;
  final long registeredAtTick;
  final long ttlTicks;

  SeenClientPositionWithTtl(int x, int y, long tick, long ttl) {
    this.position = new int[] {x, y};
    this.registeredAtTick = tick;
    this.ttlTicks = ttl;
  }

  boolean isExpired(long currentTick) {
    return currentTick - registeredAtTick >= ttlTicks;
  }
}

/** Encapsulates idle roaming state + target selection so node services keep orchestration only. */
@Slf4j
public class IdleRoamingController {
  private static final String IDLE_ROAMING_STRATEGY_RANDOM = "random";
  private static final String IDLE_ROAMING_STRATEGY_RETURN_TO_HQ = "return-to-hq";

  /** Average of past pickup positions (formerly "page-rank"). */
  private static final String IDLE_ROAMING_STRATEGY_PAST_AVG = "past-avg";

  /** Average of past pickup positions AND seen-but-not-served client positions combined. */
  private static final String IDLE_ROAMING_STRATEGY_PAST_AVG_TOTAL = "past-avg-total";

  /** Revisit a random position where a waiting client was previously seen but not served. */
  private static final String IDLE_ROAMING_STRATEGY_PAST_AVG_REVISIT = "past-avg-revisit";

  private volatile long lastIdleCheckTick = 0L;
  private volatile long lastIdleTravelPublishTick = 0L;
  private final Map<String, Random> randomTravelGeneratorPerTaxi = new ConcurrentHashMap<>();
  private final AtomicReference<Position> pendingIdleTravelTarget = new AtomicReference<>();
  private final AtomicReference<Position> currentIdleTarget = new AtomicReference<>();
  private volatile SpawnScenario activeSpawnScenario = SpawnScenario.BASELINE;
  private final List<int[]> pickupPositions = new ArrayList<>();
  private final AtomicReference<int[]> cachedHqPosition = new AtomicReference<>();

  /** TTL-based stack of positions where clients were seen but not served (with expiration). */
  private final List<SeenClientPositionWithTtl> seenClientPositions = new ArrayList<>();

  /** For visualization: the next revisit target for past-avg-revisit strategy. */
  private final AtomicReference<int[]> cachedRevisitTarget = new AtomicReference<>();

  public void setSpawnScenario(SpawnScenario scenario) {
    activeSpawnScenario = scenario == null ? SpawnScenario.BASELINE : scenario;
  }

  /** Register a pickup position to build the HQ average for past-avg strategy. */
  public synchronized void registerPickupPosition(int pickupX, int pickupY) {
    pickupPositions.add(new int[] {pickupX, pickupY});
    // Invalidate cached HQ position so it gets recalculated
    cachedHqPosition.set(null);
    log.debug(
        "Registered pickup position ({}, {}); total pickups: {}",
        pickupX,
        pickupY,
        pickupPositions.size());
  }

  /**
   * Register a position where the vehicle saw a waiting client but was unable to serve it (e.g.,
   * because it was busy). Used by past-avg-total and past-avg-revisit strategies. Positions expire
   * after a TTL configured via P2PSystemProperties.
   */
  public synchronized void registerSeenClientPosition(int x, int y, long currentSimulationTick) {
    long ttlTicks =
        Math.max(1L, Long.getLong(P2PSystemProperties.VEHICLE_IDLE_SEEN_CLIENT_TTL_TICKS, 1000L));
    seenClientPositions.add(new SeenClientPositionWithTtl(x, y, currentSimulationTick, ttlTicks));
    // Update cached revisit target to the newly seen client so the HQ visualization
    // immediately reflects seen-but-unserved clients (not just when idle roaming kicks in)
    cachedRevisitTarget.set(new int[] {x, y});
    log.debug(
        "Registered seen-but-not-served client position ({}, {}); total active: {}; TTL: {} ticks",
        x,
        y,
        countValidSeenPositions(currentSimulationTick),
        ttlTicks);
  }

  /** Clean up and count valid (non-expired) seen client positions. */
  private synchronized int countValidSeenPositions(long currentSimulationTick) {
    seenClientPositions.removeIf(sp -> sp.isExpired(currentSimulationTick));
    return seenClientPositions.size();
  }

  /** Compute and return the average HQ position from all registered pickups. */
  private synchronized int[] computeHqPosition() {
    if (pickupPositions.isEmpty()) {
      return null;
    }
    int sumX = 0;
    int sumY = 0;
    for (int[] pos : pickupPositions) {
      sumX += pos[0];
      sumY += pos[1];
    }
    return new int[] {sumX / pickupPositions.size(), sumY / pickupPositions.size()};
  }

  /** Get cached HQ position or compute it if not cached. */
  private int[] getHqPosition() {
    int[] cached = cachedHqPosition.get();
    if (cached != null) {
      return cached;
    }
    int[] computed = computeHqPosition();
    if (computed != null) {
      cachedHqPosition.set(computed);
    }
    return computed;
  }

  /**
   * Returns the current average pickup position (past-avg HQ) as [x, y], or null if no pickups have
   * been registered yet.
   */
  public int[] getHqPositionSnapshot() {
    return getHqPosition();
  }

  /**
   * Returns the combined average of pickup positions AND currently tracked seen-client positions
   * (past-avg-total HQ), or falls back to the pickup-only average if no seen clients. Does NOT
   * expire seen entries (snapshot only).
   */
  public synchronized int[] getAvgTotalPositionSnapshot() {
    int totalCount = pickupPositions.size() + seenClientPositions.size();
    if (totalCount == 0) return null;
    int sumX = 0, sumY = 0;
    for (int[] pos : pickupPositions) {
      sumX += pos[0];
      sumY += pos[1];
    }
    for (SeenClientPositionWithTtl sp : seenClientPositions) {
      sumX += sp.position[0];
      sumY += sp.position[1];
    }
    return new int[] {sumX / totalCount, sumY / totalCount};
  }

  /**
   * Returns the next revisit target for past-avg-revisit strategy, or null if none selected. Used
   * for visualization.
   */
  public int[] getRevisitTargetSnapshot() {
    return cachedRevisitTarget.get();
  }

  public void clearCurrentTarget() {
    currentIdleTarget.set(null);
  }

  public void clearAll() {
    currentIdleTarget.set(null);
    pendingIdleTravelTarget.set(null);
    lastIdleTravelPublishTick = 0L;
  }

  public void clearIfReached(Integer simulationX, Integer simulationY) {
    Position persistent = currentIdleTarget.get();
    if (persistent == null || simulationX == null || simulationY == null) {
      return;
    }
    if (simulationX == persistent.getX() && simulationY == persistent.getY()) {
      clearAll();
    }
  }

  public Optional<Position> pollIdleTravelTarget() {
    Position p = pendingIdleTravelTarget.getAndSet(null);
    return Optional.ofNullable(p);
  }

  public void checkAndTrigger(
      long currentSimulationTick,
      boolean vehicleBusy,
      boolean externallyAvailable,
      long lastActivityTick,
      Integer simulationX,
      Integer simulationY,
      int mapMaxX,
      int mapMaxY,
      String nodeId,
      Consumer<Position> publishPosition) {
    boolean enabled =
        Boolean.parseBoolean(
            System.getProperty(P2PSystemProperties.VEHICLE_ROAMING_ENABLED, "true"));
    if (!enabled) {
      return;
    }

    long idleCheckThrottleTicks =
        Math.max(1L, Long.getLong(P2PSystemProperties.VEHICLE_IDLE_CHECK_THROTTLE_TICKS, 5L));
    if (currentSimulationTick - lastIdleCheckTick < idleCheckThrottleTicks) {
      return;
    }
    lastIdleCheckTick = currentSimulationTick;

    if (vehicleBusy || !externallyAvailable) {
      lastIdleTravelPublishTick = 0L;
      return;
    }

    if (lastActivityTick < 0L) {
      return;
    }

    long idleThresholdTicks =
        Math.max(1L, Long.getLong(P2PSystemProperties.VEHICLE_IDLE_THRESHOLD_TICKS, 10L));
    long idleDurationTicks = currentSimulationTick - lastActivityTick;
    if (idleDurationTicks < idleThresholdTicks) {
      return;
    }

    if (lastIdleTravelPublishTick > 0
        && currentSimulationTick - lastIdleTravelPublishTick < idleCheckThrottleTicks) {
      return;
    }

    if (simulationX == null || simulationY == null || mapMaxX <= 0 || mapMaxY <= 0) {
      return;
    }

    Position target = currentIdleTarget.get();
    if (target == null) {
      target =
          generateIdleTarget(
              simulationX, simulationY, mapMaxX, mapMaxY, currentSimulationTick, nodeId);
      currentIdleTarget.set(target);
      if (lastIdleTravelPublishTick == 0L) {
        log.info(
            "Vehicle {} selected idle roaming target ({}, {}) strategy={} scenario={} after {} ticks of idleness",
            nodeId,
            target.getX(),
            target.getY(),
            resolveIdleRoamingStrategy(),
            activeSpawnScenario,
            idleDurationTicks);
      }
    }

    publishPosition.accept(target);
    pendingIdleTravelTarget.compareAndSet(
        null, new Position(target.getX(), target.getY(), currentSimulationTick));
    lastIdleTravelPublishTick = currentSimulationTick;
  }

  private Position generateIdleTarget(
      int currentX,
      int currentY,
      int mapMaxX,
      int mapMaxY,
      long currentSimulationTick,
      String taxi) {
    // This ensures consistency across runs for random selection
    // while maintaining independence per taxi
    randomTravelGeneratorPerTaxi.computeIfAbsent(
        taxi, t -> new Random(Long.getLong("worldSeed", 0L) ^ t.hashCode()));

    String strategy = resolveIdleRoamingStrategy();
    if (IDLE_ROAMING_STRATEGY_RETURN_TO_HQ.equals(strategy)) {
      return switch (activeSpawnScenario) {
        case BASELINE ->
            generateRandomTargetWithinRadius(currentX, currentY, mapMaxX, mapMaxY, taxi);
        case RUSH_HOUR, SPATIAL_IMBALANCE ->
            new Position(clampToMapX(mapMaxX / 2, mapMaxX), clampToMapY(mapMaxY / 2, mapMaxY));
        case SPATIAL_ISLANDS -> nearestSpatialIslandCenter(currentX, currentY, mapMaxX, mapMaxY);
      };
    }
    if (IDLE_ROAMING_STRATEGY_PAST_AVG.equals(strategy)) {
      return generatePastAvgTarget(currentX, currentY, mapMaxX, mapMaxY);
    }
    if (IDLE_ROAMING_STRATEGY_PAST_AVG_TOTAL.equals(strategy)) {
      return generatePastAvgTotalTarget(
          currentX, currentY, mapMaxX, mapMaxY, currentSimulationTick, taxi);
    }
    if (IDLE_ROAMING_STRATEGY_PAST_AVG_REVISIT.equals(strategy)) {
      return generatePastAvgRevisitTarget(
          currentX, currentY, mapMaxX, mapMaxY, currentSimulationTick, taxi);
    }
    return generateRandomTargetWithinRadius(currentX, currentY, mapMaxX, mapMaxY, taxi);
  }

  private String resolveIdleRoamingStrategy() {
    String configured =
        System.getProperty(
            P2PSystemProperties.VEHICLE_IDLE_ROAMING_STRATEGY, IDLE_ROAMING_STRATEGY_RANDOM);
    if (configured == null || configured.isBlank()) {
      return IDLE_ROAMING_STRATEGY_RANDOM;
    }
    String normalized = configured.trim().toLowerCase();
    if (IDLE_ROAMING_STRATEGY_RETURN_TO_HQ.equals(normalized)) {
      return IDLE_ROAMING_STRATEGY_RETURN_TO_HQ;
    }
    if (IDLE_ROAMING_STRATEGY_PAST_AVG.equals(normalized)) {
      return IDLE_ROAMING_STRATEGY_PAST_AVG;
    }
    if (IDLE_ROAMING_STRATEGY_PAST_AVG_TOTAL.equals(normalized)) {
      return IDLE_ROAMING_STRATEGY_PAST_AVG_TOTAL;
    }
    if (IDLE_ROAMING_STRATEGY_PAST_AVG_REVISIT.equals(normalized)) {
      return IDLE_ROAMING_STRATEGY_PAST_AVG_REVISIT;
    }
    return IDLE_ROAMING_STRATEGY_RANDOM;
  }

  /**
   * past-avg: navigates to the average of all past pickup positions. Falls back to random if no
   * pickups recorded yet.
   */
  private Position generatePastAvgTarget(int currentX, int currentY, int mapMaxX, int mapMaxY) {
    int[] hqPos = getHqPosition();
    if (hqPos != null) {
      int hqX = clampToMapX(hqPos[0], mapMaxX);
      int hqY = clampToMapY(hqPos[1], mapMaxY);
      log.info(
          "Vehicle returning to past-avg HQ position ({}, {}); computed from {} pickups",
          hqX,
          hqY,
          pickupPositions.size());
      return new Position(hqX, hqY);
    }
    log.info("No past-avg HQ recorded yet (0 pickups), falling back to not moving");
    return new Position(currentX, currentY);
  }

  /**
   * past-avg-total: navigates to the combined average of all past pickup positions AND all
   * positions where waiting clients were seen but not served (within TTL). Falls back to random if
   * neither list has data.
   */
  private synchronized Position generatePastAvgTotalTarget(
      int currentX,
      int currentY,
      int mapMaxX,
      int mapMaxY,
      long currentSimulationTick,
      String taxi) {
    countValidSeenPositions(currentSimulationTick); // Clean up expired entries
    int totalCount = pickupPositions.size() + seenClientPositions.size();
    if (totalCount == 0) {
      log.info("No past-avg-total data recorded yet, falling back to random roaming");
      return generateRandomTargetWithinRadius(currentX, currentY, mapMaxX, mapMaxY, taxi);
    }
    int sumX = 0;
    int sumY = 0;
    for (int[] pos : pickupPositions) {
      sumX += pos[0];
      sumY += pos[1];
    }
    for (SeenClientPositionWithTtl sp : seenClientPositions) {
      sumX += sp.position[0];
      sumY += sp.position[1];
    }
    int avgX = clampToMapX(sumX / totalCount, mapMaxX);
    int avgY = clampToMapY(sumY / totalCount, mapMaxY);
    log.info(
        "Vehicle returning to past-avg-total position ({}, {}); computed from {} pickups + {} active seen clients",
        avgX,
        avgY,
        pickupPositions.size(),
        seenClientPositions.size());
    return new Position(avgX, avgY);
  }

  /**
   * past-avg-revisit: returns to a random position where a waiting client was previously seen but
   * not served (within TTL). Falls back to random if no valid seen client positions remain.
   */
  private synchronized Position generatePastAvgRevisitTarget(
      int currentX,
      int currentY,
      int mapMaxX,
      int mapMaxY,
      long currentSimulationTick,
      String taxi) {
    countValidSeenPositions(currentSimulationTick); // Clean up expired entries
    if (seenClientPositions.isEmpty()) {
      log.info(
          "No past-avg-revisit data recorded yet (0 valid seen clients), falling back to random roaming");
      cachedRevisitTarget.set(null);
      return generateRandomTargetWithinRadius(currentX, currentY, mapMaxX, mapMaxY, taxi);
    }
    // Pick a random previously seen client position to revisit
    int idx = randomTravelGeneratorPerTaxi.get(taxi).nextInt(seenClientPositions.size());
    int[] revisit = seenClientPositions.get(idx).position;
    int targetX = clampToMapX(revisit[0], mapMaxX);
    int targetY = clampToMapY(revisit[1], mapMaxY);
    // Cache the revisit target for visualization (HQ display)
    cachedRevisitTarget.set(new int[] {targetX, targetY});
    log.info(
        "Vehicle revisiting seen-client position ({}, {}) [index={} of {} active]",
        targetX,
        targetY,
        idx,
        seenClientPositions.size());
    return new Position(targetX, targetY);
  }

  private Position nearestSpatialIslandCenter(
      int currentX, int currentY, int mapMaxX, int mapMaxY) {
    int[][] centers = islandCentres(mapMaxX, mapMaxY);
    int bestX = clampToMapX(mapMaxX / 2, mapMaxX);
    int bestY = clampToMapY(mapMaxY / 2, mapMaxY);
    double bestDistance = Double.MAX_VALUE;
    for (int[] center : centers) {
      double distance = P2PGeoUtils.distance(currentX, currentY, center[0], center[1]);
      if (distance < bestDistance) {
        bestDistance = distance;
        bestX = center[0];
        bestY = center[1];
      }
    }
    return new Position(bestX, bestY);
  }

  private int[][] islandCentres(int mapMaxX, int mapMaxY) {
    int spread = (int) (Math.min(mapMaxX, mapMaxY) * 0.08);
    int ax = mapMaxX / 4;
    int ay = (int) (mapMaxY * 0.28);
    int bx = 3 * mapMaxX / 4;
    int by = (int) (mapMaxY * 0.72);
    return new int[][] {
      {clampToMapX(ax - spread, mapMaxX), clampToMapY(ay, mapMaxY)},
      {clampToMapX(ax + spread, mapMaxX), clampToMapY(ay, mapMaxY)},
      {clampToMapX(ax, mapMaxX), clampToMapY(ay - spread, mapMaxY)},
      {clampToMapX(bx - spread, mapMaxX), clampToMapY(by, mapMaxY)},
      {clampToMapX(bx + spread, mapMaxX), clampToMapY(by, mapMaxY)},
      {clampToMapX(bx, mapMaxX), clampToMapY(by + spread, mapMaxY)}
    };
  }

  private Position generateRandomTargetWithinRadius(
      int currentX, int currentY, int mapMaxX, int mapMaxY, String taxi) {
    int maxDistanceMeters =
        Math.max(
            1,
            Integer.getInteger(
                P2PSystemProperties.VEHICLE_RANDOM_TRAVEL_MAX_DISTANCE_METERS, 20000));

    double angle = randomTravelGeneratorPerTaxi.get(taxi).nextDouble() * 2 * Math.PI;
    double distance = randomTravelGeneratorPerTaxi.get(taxi).nextDouble() * maxDistanceMeters;

    int marginX = Math.max(1, mapMaxX / 50);
    int marginY = Math.max(1, mapMaxY / 50);
    int targetX =
        Math.max(
            marginX,
            Math.min(mapMaxX - marginX, (int) Math.round(currentX + distance * Math.cos(angle))));
    int targetY =
        Math.max(
            marginY,
            Math.min(mapMaxY - marginY, (int) Math.round(currentY + distance * Math.sin(angle))));

    if (targetX == currentX && targetY == currentY) {
      targetX = mapMaxX / 2;
      targetY = mapMaxY / 2;
    }

    return new Position(targetX, targetY);
  }

  private int clampToMapX(int value, int mapMaxX) {
    return Math.max(0, Math.min(Math.max(0, mapMaxX - 1), value));
  }

  private int clampToMapY(int value, int mapMaxY) {
    return Math.max(0, Math.min(Math.max(0, mapMaxY - 1), value));
  }
}
