package de.sikeller.aqs.p2p.service.config;

/** Configuration accessor abstraction for P2P system properties. */
public interface P2PConfig {
  int overlayMinNeighbors();

  int overlayMaxNeighbors();

  double overlayMaxDistance();

  String overlayShortcutStrategy();

  int overlayShortcuts();

  long positionTtlTicks();

  long positionRevisionThrottleTicks();

  int positionRevisionMinMoveMeters();
}
