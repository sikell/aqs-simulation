package de.sikeller.aqs.p2p.util;

public final class P2PGeoUtils {
  private P2PGeoUtils() {}

  public static double distance(int x1, int y1, int x2, int y2) {
    double dx = x1 - x2;
    double dy = y1 - y2;
    return Math.sqrt(dx * dx + dy * dy);
  }

  public static int etaSeconds(int startX, int startY, int targetX, int targetY, double speedMps) {
    double safeSpeed = Math.max(0.1, speedMps);
    double distance = distance(startX, startY, targetX, targetY);
    return Math.max(1, (int) Math.round(distance / safeSpeed));
  }
}
