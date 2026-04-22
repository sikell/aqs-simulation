package de.sikeller.aqs.p2p.service.overlay;

import de.sikeller.aqs.p2p.api.NodeDescriptor;
import de.sikeller.aqs.p2p.service.position.PositionManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Deterministic ring-based shortcut strategy: evenly spaced offsets on the ring.
 */
public class RingShortcutStrategy implements ShortcutStrategy {
  @Override
  public List<NodeDescriptor> selectShortcuts(
      NodeDescriptor self,
      List<NodeDescriptor> sortedPeers,
      int startIndex,
      Set<String> excludedPeerIds,
      int limit,
      PositionManager positionManager) {
    if (limit <= 0 || sortedPeers == null || sortedPeers.isEmpty()) {
      return List.of();
    }
    int maxOffset = sortedPeers.size();
    Set<String> usedIds = new HashSet<>();
    if (excludedPeerIds != null) usedIds.addAll(excludedPeerIds);
    List<NodeDescriptor> shortcuts = new ArrayList<>();
    for (int slot = 1; slot <= limit; slot++) {
      int suggestedOffset = Math.max(1, (int) Math.round((double) slot * maxOffset / (limit + 1.0)));
      for (int shift = 0; shift < maxOffset; shift++) {
        int offset = ((suggestedOffset - 1 + shift) % maxOffset) + 1;
        NodeDescriptor candidate = sortedPeers.get((startIndex + offset - 1) % maxOffset);
        if (!usedIds.add(candidate.id())) continue;
        shortcuts.add(candidate);
        break;
      }
      if (shortcuts.size() >= limit) break;
    }
    return shortcuts;
  }
}

