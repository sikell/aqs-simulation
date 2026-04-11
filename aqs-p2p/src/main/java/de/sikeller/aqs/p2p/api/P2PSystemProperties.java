package de.sikeller.aqs.p2p.api;

/**
 * Central keys for system properties used by the P2P runtime.
 */
public final class P2PSystemProperties {
  private P2PSystemProperties() {}

  public static final String OVERLAY_MAX_NEIGHBORS = "aqs.p2p.overlay.maxNeighbors";
  public static final String OVERLAY_SHORTCUTS = "aqs.p2p.overlay.shortcuts";
  public static final String OVERLAY_COLLECTOR_NODE_ID = "aqs.p2p.overlay.collectorNodeId";
  public static final String OVERLAY_PIN_COLLECTOR = "aqs.p2p.overlay.pinCollector";

  public static final String VEHICLE_OPEN_REQUEST_STRATEGY = "aqs.p2p.vehicle.openRequestStrategy";
  public static final String VEHICLE_COMMIT_LEASE_TICKS = "aqs.p2p.vehicle.commitLeaseTicks";
  public static final String VEHICLE_ASSUMED_SPEED_MPS = "aqs.p2p.vehicle.assumedSpeedMps";
  public static final String VEHICLE_REBID_MIN_INTERVAL_TICKS = "aqs.p2p.vehicle.rebidMinIntervalTicks";
  public static final String VEHICLE_REBID_MIN_ETA_IMPROVEMENT_SECONDS =
      "aqs.p2p.vehicle.rebidMinEtaImprovementSeconds";
  public static final String VEHICLE_REBID_MOVE_DISTANCE_METERS = "aqs.p2p.vehicle.rebidMoveDistanceMeters";
  public static final String VEHICLE_REQUEST_CACHE_TTL_TICKS = "aqs.p2p.vehicle.requestCacheTtlTicks";
  public static final String VEHICLE_ALLOW_OUTSIDE_CLIENT_RANGE = "aqs.p2p.vehicle.allowOutsideClientRange";
}

