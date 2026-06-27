package de.sikeller.aqs.p2p.service.config;

import de.sikeller.aqs.p2p.api.P2PSystemProperties;

/**
 * System-property backed P2PConfig. Centralises access to system properties used by the P2P
 * runtime.
 */
public class SystemPropertyP2PConfig implements P2PConfig {
  @Override
  public int overlayMinNeighbors() {
    String configured = System.getProperty(P2PSystemProperties.OVERLAY_MIN_NEIGHBORS, "").trim();
    if (configured.isBlank()) return 0;
    int parsed = parseIntOrDefault(configured, 0);
    return Math.max(0, parsed);
  }

  @Override
  public int overlayMaxNeighbors() {
    String configured = System.getProperty(P2PSystemProperties.OVERLAY_MAX_NEIGHBORS, "").trim();
    if (configured.isBlank()) return Integer.MAX_VALUE;
    int parsed = parseIntOrDefault(configured, Integer.MAX_VALUE);
    return Math.max(1, parsed);
  }

  @Override
  public double overlayMaxDistance() {
    String configured = System.getProperty(P2PSystemProperties.OVERLAY_MAX_DISTANCE, "").trim();
    if (configured.isBlank()) return Double.MAX_VALUE;
    double parsed = parseDoubleOrDefault(configured, Double.MAX_VALUE);
    return Math.max(0d, parsed);
  }

  @Override
  public String overlayShortcutStrategy() {
    return System.getProperty(P2PSystemProperties.OVERLAY_SHORTCUT_STRATEGY, "kleinberg")
        .trim()
        .toLowerCase();
  }

  @Override
  public int overlayShortcuts() {
    String configured = System.getProperty(P2PSystemProperties.OVERLAY_SHORTCUTS, "").trim();
    if (configured.isBlank()) return 1;
    return Math.max(0, parseIntOrDefault(configured, 1));
  }

  @Override
  public long positionTtlTicks() {
    String val = System.getProperty(P2PSystemProperties.OVERLAY_POSITION_TTL_TICKS, "").trim();
    if (val.isBlank()) return 200L;
    long parsed = parseLongOrDefault(val, 200L);
    return Math.max(1L, parsed);
  }

  @Override
  public long positionRevisionThrottleTicks() {
    String val =
        System.getProperty(P2PSystemProperties.OVERLAY_POSITION_REVISION_THROTTLE_TICKS, "").trim();
    if (val.isBlank()) return 5L;
    long parsed = parseLongOrDefault(val, 5L);
    return Math.max(1L, parsed);
  }

  @Override
  public int positionRevisionMinMoveMeters() {
    String val =
        System.getProperty(P2PSystemProperties.OVERLAY_POSITION_REVISION_MIN_MOVE_METERS, "")
            .trim();
    if (val.isBlank()) return 50;
    int parsed = parseIntOrDefault(val, 50);
    return Math.max(0, parsed);
  }

  private static int parseIntOrDefault(String s, int defaultVal) {
    try {
      return Integer.parseInt(s);
    } catch (NumberFormatException e) {
      return defaultVal;
    }
  }

  private static long parseLongOrDefault(String s, long defaultVal) {
    try {
      return Long.parseLong(s);
    } catch (NumberFormatException e) {
      return defaultVal;
    }
  }

  private static double parseDoubleOrDefault(String s, double defaultVal) {
    try {
      return Double.parseDouble(s);
    } catch (NumberFormatException e) {
      return defaultVal;
    }
  }
}
