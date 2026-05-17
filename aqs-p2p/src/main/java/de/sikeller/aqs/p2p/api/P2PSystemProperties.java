package de.sikeller.aqs.p2p.api;

/** Central keys for system properties used by the P2P runtime. */
public final class P2PSystemProperties {
  private P2PSystemProperties() {}

  public static final String OVERLAY_MIN_NEIGHBORS = "aqs.p2p.overlay.minNeighbors";
  public static final String OVERLAY_MAX_NEIGHBORS = "aqs.p2p.overlay.maxNeighbors";
  public static final String OVERLAY_MAX_DISTANCE = "aqs.p2p.overlay.maxDistance";
  public static final String OVERLAY_SHORTCUTS = "aqs.p2p.overlay.shortcuts";
  public static final String OVERLAY_COLLECTOR_NODE_ID = "aqs.p2p.overlay.collectorNodeId";
  public static final String OVERLAY_PIN_COLLECTOR = "aqs.p2p.overlay.pinCollector";
  public static final String OVERLAY_POSITION_TTL_TICKS = "aqs.p2p.overlay.positionTtlTicks";
  // Throttle position revision bumps to avoid cache thrashing (ticks)
  public static final String OVERLAY_POSITION_REVISION_THROTTLE_TICKS =
      "aqs.p2p.overlay.positionRevisionThrottleTicks";
  // Minimum move (meters) required to bump position revision
  public static final String OVERLAY_POSITION_REVISION_MIN_MOVE_METERS =
      "aqs.p2p.overlay.positionRevisionMinMoveMeters";

  // Overlay shortcut / small-world tuning
  public static final String OVERLAY_SHORTCUT_STRATEGY = "aqs.p2p.overlay.shortcut.strategy";
  public static final String OVERLAY_SHORTCUT_KLEINBERG_R = "aqs.p2p.overlay.shortcut.kleinberg.r";
  public static final String OVERLAY_SHORTCUT_NODE_PROBABILITY =
      "aqs.p2p.overlay.shortcut.nodeProbability";

  // Inbox sizing
  public static final String MAX_INBOX_MESSAGES = "aqs.p2p.maxInboxMessages";

  public static final String VEHICLE_OPEN_REQUEST_STRATEGY = "aqs.p2p.vehicle.openRequestStrategy";
  public static final String VEHICLE_COMMIT_LEASE_TICKS = "aqs.p2p.vehicle.commitLeaseTicks";
  public static final String VEHICLE_ASSUMED_SPEED_MPS = "aqs.p2p.vehicle.assumedSpeedMps";
  public static final String VEHICLE_REOFFER_MIN_INTERVAL_TICKS =
      "aqs.p2p.vehicle.reofferMinIntervalTicks";
  public static final String VEHICLE_REOFFER_MOVE_DISTANCE_METERS =
      "aqs.p2p.vehicle.reofferMoveDistanceMeters";
  public static final String VEHICLE_REQUEST_CACHE_TTL_TICKS =
      "aqs.p2p.vehicle.requestCacheTtlTicks";
  public static final String VEHICLE_ALLOW_OUTSIDE_CLIENT_RANGE =
      "aqs.p2p.vehicle.allowOutsideClientRange";

  // Idle vehicle random travel feature
  public static final String VEHICLE_IDLE_RANDOM_TRAVEL_ENABLED =
      "aqs.p2p.vehicle.idleRandomTravelEnabled";
  public static final String VEHICLE_IDLE_THRESHOLD_TICKS = "aqs.p2p.vehicle.idleThresholdTicks";
  public static final String VEHICLE_IDLE_CHECK_THROTTLE_TICKS =
      "aqs.p2p.vehicle.idleCheckThrottleTicks";
  public static final String VEHICLE_RANDOM_TRAVEL_MAX_DISTANCE_METERS =
      "aqs.p2p.vehicle.randomTravelMaxDistanceMeters";
  public static final String VEHICLE_IDLE_ROAMING_STRATEGY = "aqs.p2p.vehicle.idleRoamingStrategy";
}
