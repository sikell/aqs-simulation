package de.sikeller.aqs.taxi.algorithm.collector;

import de.sikeller.aqs.p2p.api.NodeDescriptor;
import de.sikeller.aqs.p2p.api.NodeRole;
import de.sikeller.aqs.p2p.api.P2PNetwork;
import java.util.List;

/** Discovers vehicle peers and returns the best settled snapshot within the wait budget. */
public class TaxiCountDerivationService {
  private static final int DISCOVERY_POLL_MS = 250;

  public List<NodeDescriptor> discoverVehiclePeers(P2PNetwork network, int waitMs, int settleMs) {
    if (network == null) {
      return List.of();
    }

    long deadline = System.currentTimeMillis() + Math.max(0, waitMs);
    List<NodeDescriptor> bestVehicles = List.of();
    long lastChangeAt = System.currentTimeMillis();
    long lastCount = -1;

    while (System.currentTimeMillis() < deadline) {
      List<NodeDescriptor> currentVehicles =
          network.peers().stream()
              .filter(peer -> peer.role() == NodeRole.VEHICLE)
              .sorted(java.util.Comparator.comparing(NodeDescriptor::id))
              .toList();

      if (currentVehicles.size() != lastCount) {
        lastCount = currentVehicles.size();
        lastChangeAt = System.currentTimeMillis();
      }
      if (currentVehicles.size() > bestVehicles.size()) {
        bestVehicles = currentVehicles;
      }
      if (!bestVehicles.isEmpty() && System.currentTimeMillis() - lastChangeAt >= Math.max(0, settleMs)) {
        break;
      }

      try {
        Thread.sleep(DISCOVERY_POLL_MS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }

    return bestVehicles;
  }
}

