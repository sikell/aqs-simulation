package de.sikeller.aqs.model;

import java.util.Map;
import java.util.Set;

/** Optionales UI-Status-Interface fuer Algorithmen mit P2P-Laufzeitdaten. */
public interface P2PStatusProvider {
  Map<String, String> getP2PStatus();

  default P2PNetworkSnapshot getP2PNetworkSnapshot() {
    return P2PNetworkSnapshot.empty();
  }

  default void requestP2PTopologyScan() {}

  default Map<String, Set<String>> getTaxiKnowledgeByClientIds() {
    return Map.of();
  }

  /**
   * Returns the current past-avg HQ position per taxi name as [x, y], or an empty map if not
   * available.
   */
  default Map<String, int[]> getPageRankHqPositions() {
    return Map.of();
  }
}
