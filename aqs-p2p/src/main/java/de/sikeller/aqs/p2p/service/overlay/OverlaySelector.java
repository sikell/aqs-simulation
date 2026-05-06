package de.sikeller.aqs.p2p.service.overlay;

import de.sikeller.aqs.p2p.api.NodeDescriptor;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Overlay selection responsibilities (peer selection, shortcuts). */
public interface OverlaySelector {

  /** Select an overlay for the given topic and peer list. */
  OverlaySelection select(String topic, List<NodeDescriptor> peers);

  /** Convenience: return the set of neighbor ids used for a topic. */
  default Set<String> overlayNeighborIds(String topic, List<NodeDescriptor> peers) {
    return select(topic, peers).peers().stream()
        .map(NodeDescriptor::id)
        .collect(Collectors.toSet());
  }

  record OverlaySelection(List<NodeDescriptor> peers, Set<String> shortcutPeerIds) {}
}
