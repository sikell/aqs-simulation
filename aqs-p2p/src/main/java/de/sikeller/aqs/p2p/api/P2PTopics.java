package de.sikeller.aqs.p2p.api;

/**
 * Canonical topic names for the P2P ride and topology protocols.
 */
public final class P2PTopics {
  private P2PTopics() {}

  public static final String TOPOLOGY_SCAN_REQUEST = "topology.scan.request";
  public static final String TOPOLOGY_SCAN_RESPONSE = "topology.scan.response";
  public static final String RIDE_REQUEST = "ride.request";
  public static final String RIDE_OFFER = "ride.offer";
  public static final String RIDE_ACCEPT = "ride.accept";
  public static final String RIDE_COMMIT = "ride.commit";
  public static final String VEHICLE_POSITION = "vehicle.position";
}

