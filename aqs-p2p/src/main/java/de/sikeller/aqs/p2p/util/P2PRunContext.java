package de.sikeller.aqs.p2p.util;

public final class P2PRunContext {
  private static final ThreadLocal<Long> WORLD_SEED = new ThreadLocal<>();

  private P2PRunContext() {}

  public static void setWorldSeed(long seed) {
    WORLD_SEED.set(seed);
  }

  public static long worldSeed() {
    Long seed = WORLD_SEED.get();
    return seed != null ? seed : Long.getLong("worldSeed", 0L);
  }

  public static void clear() {
    WORLD_SEED.remove();
  }
}
