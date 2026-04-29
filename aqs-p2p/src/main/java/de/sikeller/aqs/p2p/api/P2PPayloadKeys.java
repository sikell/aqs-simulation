package de.sikeller.aqs.p2p.api;

/** Shared payload field keys used by P2P topics. */
public final class P2PPayloadKeys {
  private P2PPayloadKeys() {}

  public static final String ORIGIN_NODE = "originNode";
  public static final String FROM = "from";
  public static final String TO = "to";
  public static final String HOPS_REMAINING = "hopsRemaining";

  public static final String CLIENT_NAME = "clientName";
  public static final String REQUEST_ID = "requestId";
  public static final String REQUEST_X = "requestX";
  public static final String REQUEST_Y = "requestY";
  public static final String SEARCH_RADIUS = "searchRadius";
  public static final String FORWARDED_BY = "forwardedBy";
  public static final String VEHICLE = "vehicle";
  public static final String TAXI_NAME = "taxiName";
  public static final String WINNER_VEHICLE = "winnerVehicle";

  public static final String ROLE = "role";
  public static final String NEIGHBORS = "neighbors";
  public static final String SHORTCUT_NEIGHBORS = "shortcutNeighbors";

  public static final String POSITION_X = "x";
  public static final String POSITION_Y = "y";
  public static final String POSITION_TICK = "tick";
}

