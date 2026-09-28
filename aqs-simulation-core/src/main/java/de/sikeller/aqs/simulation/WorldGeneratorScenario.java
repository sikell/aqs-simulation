package de.sikeller.aqs.simulation;

import de.sikeller.aqs.model.Position;
import de.sikeller.aqs.model.SpawnScenario;
import de.sikeller.aqs.model.World;
import de.sikeller.aqs.model.WorldObject;
import java.util.Map;
import java.util.Random;
import lombok.extern.slf4j.Slf4j;

/**
 * World generator that supports selectable client spawn scenarios (S1–S3). The scenario is
 * controlled by the integer parameter {@code spawnScenario} (ordinal of {@link SpawnScenario}).
 *
 * <ul>
 *   <li>S1 BASELINE – uniform random spawn times and positions (identical to legacy behaviour).
 *   <li>S2 RUSH_HOUR – two Gaussian peaks at 7/24 and 17/24 of the spawn window.
 *   <li>S3 SPATIAL_IMBALANCE – 60 % of clients spawn in a CBD hotspot around the map centre.
 * </ul>
 */
@Slf4j
public class WorldGeneratorScenario implements WorldGenerator {

  private static final String PARAM_SPAWN_SCENARIO = "spawnScenario";

  @Override
  public void init(WorldObject world, Map<String, Integer> parameters) {
    int seed = parameters.getOrDefault("worldSeed", 1);
    Random random = new Random(seed);

    SpawnScenario scenario =
        SpawnScenario.fromOrdinal(parameters.getOrDefault(PARAM_SPAWN_SCENARIO, 0));

    log.info("Initialize world with scenario={} parameters={}", scenario, parameters);

    int mapSize = parameters.getOrDefault("mapSize", 40000);
    world.reset(new World.WorldSize(mapSize, mapSize));
    generateTaxis(world, parameters, random);
    generateClients(world, parameters, random, scenario);
  }

  // -------------------------------------------------------------------------
  // Taxi generation (unchanged from WorldGeneratorRandom)
  // -------------------------------------------------------------------------

  private void generateTaxis(WorldObject world, Map<String, Integer> parameters, Random random) {
    int taxiCount = parameters.get("taxiCount");
    int taxiSeatCount = parameters.getOrDefault("taxiSeatCount", 2);
    int taxiSpeed = parameters.getOrDefault("taxiSpeed", 50);

    for (int i = 0; i < taxiCount; i++) {
      world.addTaxi("t" + i, taxiSeatCount, randomPosition(world, random), taxiSpeed);
    }
  }

  // -------------------------------------------------------------------------
  // Client generation
  // -------------------------------------------------------------------------

  private void generateClients(
      WorldObject world,
      Map<String, Integer> parameters,
      Random random,
      SpawnScenario scenario) {
    int clientCount = parameters.get("clientCount");
    int clientSpawnWindow = parameters.getOrDefault("clientSpawnWindow", 0);
    int clientSpeed = parameters.getOrDefault("clientSpeed", 5);

    for (int i = 0; i < clientCount; i++) {
      int spawnTime = spawnTime(clientSpawnWindow, random, scenario);
      Position from = spawnPosition(world, random, scenario);
      Position to = randomPosition(world, random);
      world.addClient("c" + i, spawnTime, from, to, clientSpeed);
    }
  }

  // -------------------------------------------------------------------------
  // Spawn time strategies
  // -------------------------------------------------------------------------

  private int spawnTime(int window, Random random, SpawnScenario scenario) {
    if (window == 0) {
      return 0;
    }
    return switch (scenario) {
      case BASELINE, SPATIAL_IMBALANCE, SPATIAL_ISLANDS -> random.nextInt(window);
      case RUSH_HOUR -> rushHourSpawnTime(window, random);
      default -> random.nextInt(window);
    };
  }

  /**
   * Two Gaussian rush-hour peaks (7/24 and 17/24 of the spawn window) with σ = 1/24 of the
   * window. 30 % of clients are assigned to a uniform background distribution.
   */
  private int rushHourSpawnTime(int window, Random random) {
    double sigma = window / 24.0;
    double roll = random.nextDouble();

    int t;
    if (roll < 0.30) {
      // background traffic – uniform random
      t = random.nextInt(window);
    } else if (roll < 0.65) {
      // morning peak: 7/24
      double mean = window * (7.0 / 24.0);
      t = (int) Math.round(mean + random.nextGaussian() * sigma);
    } else {
      // evening peak: 17/24
      double mean = window * (17.0 / 24.0);
      t = (int) Math.round(mean + random.nextGaussian() * sigma);
    }
    return Math.max(0, Math.min(window - 1, t));
  }

  // -------------------------------------------------------------------------
  // Spawn position strategies
  // -------------------------------------------------------------------------

  private Position spawnPosition(World world, Random random, SpawnScenario scenario) {
    return switch (scenario) {
      case SPATIAL_IMBALANCE -> spatialImbalancePosition(world, random);
      case SPATIAL_ISLANDS -> spatialIslandsPosition(world, random);
      case BASELINE, RUSH_HOUR -> randomPosition(world, random);
    };
  }

  /**
   * S3: 60 % of clients originate inside a circular CBD hotspot centred on the map with radius
   * min(maxX, maxY) / 6. The remaining 40 % spawn uniformly.
   */
  private Position spatialImbalancePosition(World world, Random random) {
    if (random.nextDouble() < 0.60) {
      int cx = world.getSize().getMaxX() / 2;
      int cy = world.getSize().getMaxY() / 2;
      int radius = Math.min(world.getSize().getMaxX(), world.getSize().getMaxY()) / 6;
      // Rejection sampling for uniform distribution inside circle
      for (int attempt = 0; attempt < 1000; attempt++) {
        int dx = random.nextInt(2 * radius + 1) - radius;
        int dy = random.nextInt(2 * radius + 1) - radius;
        if ((long) dx * dx + (long) dy * dy <= (long) radius * radius) {
          int x = Math.max(0, Math.min(world.getSize().getMaxX() - 1, cx + dx));
          int y = Math.max(0, Math.min(world.getSize().getMaxY() - 1, cy + dy));
          return new Position(x, y);
        }
      }
    }
    return randomPosition(world, random);
  }

  private Position randomPosition(World world, Random random) {
    return new Position(random.nextInt(world.getSize().getMaxX()), random.nextInt(world.getSize().getMaxY()));
  }

  // -------------------------------------------------------------------------
  // S4 – Spatial Islands
  // -------------------------------------------------------------------------

  /**
   * Two geographic clusters, each containing 3 tightly-spaced islands (archipelago style).
   *
   * <ul>
   *   <li>Cluster A (north-west): centred at (25 %, 28 %) of the map, islands spread ±6 % apart.
   *   <li>Cluster B (south-east): centred at (75 %, 72 %) of the map, islands spread ±6 % apart.
   * </ul>
   *
   * 80 % of clients spawn inside one of the six islands (random island chosen uniformly within a
   * random cluster), the remaining 20 % spawn uniformly to model sparse inter-cluster travel.
   */
  private static final double ISLAND_RADIUS_FACTOR = 0.06; // fraction of min(maxX,maxY)
  private static final double ISLAND_SPAWN_FRACTION = 0.80;

  private Position spatialIslandsPosition(World world, Random random) {
    if (random.nextDouble() < ISLAND_SPAWN_FRACTION) {
      int[][] centres = islandCentres(world);
      int[] centre = centres[random.nextInt(centres.length)];
      int radius = (int) (Math.min(world.getSize().getMaxX(), world.getSize().getMaxY()) * ISLAND_RADIUS_FACTOR);
      radius = Math.max(1, radius);
      for (int attempt = 0; attempt < 1000; attempt++) {
        int dx = random.nextInt(2 * radius + 1) - radius;
        int dy = random.nextInt(2 * radius + 1) - radius;
        if ((long) dx * dx + (long) dy * dy <= (long) radius * radius) {
          int x = Math.max(0, Math.min(world.getSize().getMaxX() - 1, centre[0] + dx));
          int y = Math.max(0, Math.min(world.getSize().getMaxY() - 1, centre[1] + dy));
          return new Position(x, y);
        }
      }
    }
    return randomPosition(world, random);
  }

  /**
   * Returns 6 island centres arranged as two tight clusters:
   *
   * <pre>
   *   Cluster A (NW) – centred at (25 %, 28 %):
   *     A1 = centre + (-6 %,  0 %)   A2 = centre + (+6 %,  0 %)   A3 = centre + (0 %, -8 %)
   *   Cluster B (SE) – centred at (75 %, 72 %):
   *     B1 = centre + (-6 %,  0 %)   B2 = centre + (+6 %,  0 %)   B3 = centre + (0 %, +8 %)
   * </pre>
   */
  private static int[][] islandCentres(World world) {
    int w = world.getSize().getMaxX();
    int h = world.getSize().getMaxY();
    int spread = (int) (Math.min(w, h) * 0.08); // distance between islands inside a cluster

    // Cluster A – north-west
    int ax = w / 4;
    int ay = (int) (h * 0.28);
    // Cluster B – south-east
    int bx = 3 * w / 4;
    int by = (int) (h * 0.72);

    return new int[][] {
      // Cluster A
      {ax - spread, ay},
      {ax + spread, ay},
      {ax, ay - spread},
      // Cluster B
      {bx - spread, by},
      {bx + spread, by},
      {bx, by + spread},
    };
  }
}

