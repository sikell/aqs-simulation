package de.sikeller.aqs.p2p.service.overlay;

import de.sikeller.aqs.p2p.api.NodeDescriptor;
import de.sikeller.aqs.p2p.service.position.PositionManager;
import java.util.List;
import java.util.Set;

/** Strategy interface to select shortcut peers for the overlay. */
public interface ShortcutStrategy {
  List<NodeDescriptor> selectShortcuts(
      NodeDescriptor self,
      List<NodeDescriptor> sortedPeers,
      int startIndex,
      Set<String> excludedPeerIds,
      int limit,
      PositionManager positionManager);
}
