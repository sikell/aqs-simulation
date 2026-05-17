package de.sikeller.aqs.p2p.util;

import de.sikeller.aqs.model.Position;
import de.sikeller.aqs.model.SpawnScenario;
import de.sikeller.aqs.p2p.api.P2PSystemProperties;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

/**
 * Encapsulates idle roaming state + target selection so node services keep orchestration only.
 */
@Slf4j
public class IdleRoamingController {
  private static final String IDLE_ROAMING_STRATEGY_RANDOM = "random";
  private static final String IDLE_ROAMING_STRATEGY_PAGE_RANK = "page-rank";

  private volatile long lastIdleCheckTick = 0L;
  private volatile long lastIdleTravelPublishTick = 0L;
  private final Random randomTravelGenerator = new Random();
  private final AtomicReference<Position> pendingIdleTravelTarget = new AtomicReference<>();
  private final AtomicReference<Position> currentIdleTarget = new AtomicReference<>();
  private volatile SpawnScenario activeSpawnScenario = SpawnScenario.BASELINE;

  public void setSpawnScenario(SpawnScenario scenario) {
    activeSpawnScenario = scenario == null ? SpawnScenario.BASELINE : scenario;
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
            System.getProperty(P2PSystemProperties.VEHICLE_IDLE_RANDOM_TRAVEL_ENABLED, "true"));
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
      target = generateIdleTarget(simulationX, simulationY, mapMaxX, mapMaxY);
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

  private Position generateIdleTarget(int currentX, int currentY, int mapMaxX, int mapMaxY) {
    String strategy = resolveIdleRoamingStrategy();
    if (IDLE_ROAMING_STRATEGY_PAGE_RANK.equals(strategy)) {
      return generatePageRankLikeTarget(currentX, currentY, mapMaxX, mapMaxY);
    }
    return generateRandomTargetWithinRadius(currentX, currentY, mapMaxX, mapMaxY);
  }

  private String resolveIdleRoamingStrategy() {
    String configured =
        System.getProperty(
            P2PSystemProperties.VEHICLE_IDLE_ROAMING_STRATEGY, IDLE_ROAMING_STRATEGY_RANDOM);
    if (configured == null || configured.isBlank()) {
      return IDLE_ROAMING_STRATEGY_RANDOM;
    }
    String normalized = configured.trim().toLowerCase();
    return IDLE_ROAMING_STRATEGY_PAGE_RANK.equals(normalized)
        ? IDLE_ROAMING_STRATEGY_PAGE_RANK
        : IDLE_ROAMING_STRATEGY_RANDOM;
  }

  private Position generatePageRankLikeTarget(int currentX, int currentY, int mapMaxX, int mapMaxY) {
    return switch (activeSpawnScenario) {
      case BASELINE -> generateRandomTargetWithinRadius(currentX, currentY, mapMaxX, mapMaxY);
      case RUSH_HOUR, SPATIAL_IMBALANCE ->
          new Position(clampToMapX(mapMaxX / 2, mapMaxX), clampToMapY(mapMaxY / 2, mapMaxY));
      case SPATIAL_ISLANDS -> nearestSpatialIslandCenter(currentX, currentY, mapMaxX, mapMaxY);
    };
  }

  private Position nearestSpatialIslandCenter(int currentX, int currentY, int mapMaxX, int mapMaxY) {
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

  private Position generateRandomTargetWithinRadius(int currentX, int currentY, int mapMaxX, int mapMaxY) {
    int maxDistanceMeters =
        Math.max(
            1,
            Integer.getInteger(
                P2PSystemProperties.VEHICLE_RANDOM_TRAVEL_MAX_DISTANCE_METERS, 20000));

    double angle = randomTravelGenerator.nextDouble() * 2 * Math.PI;
    double distance = randomTravelGenerator.nextDouble() * maxDistanceMeters;

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

