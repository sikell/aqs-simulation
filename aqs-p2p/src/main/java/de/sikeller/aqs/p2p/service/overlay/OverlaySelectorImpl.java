package de.sikeller.aqs.p2p.service.overlay;

import de.sikeller.aqs.p2p.api.NodeDescriptor;
import de.sikeller.aqs.p2p.api.NodeRole;
import de.sikeller.aqs.p2p.api.P2PSystemProperties;
import de.sikeller.aqs.p2p.api.P2PTopics;
import de.sikeller.aqs.p2p.service.config.P2PConfig;
import de.sikeller.aqs.p2p.service.position.PositionManager;
import de.sikeller.aqs.p2p.util.P2PRunContext;
import java.util.*;
import lombok.extern.slf4j.Slf4j;

/**
 * Advanced overlay selector ported from previous in-class implementation. This implementation is
 * self-contained and uses {@link PositionManager} and {@link P2PConfig} for tunables and position
 * snapshots.
 */
@Slf4j
public class OverlaySelectorImpl implements OverlaySelector {
  private final NodeDescriptor self;
  private final PositionManager positionManager;
  private final P2PConfig config;
  private final ShortcutStrategy shortcutStrategy;

  public OverlaySelectorImpl(
      NodeDescriptor self, PositionManager positionManager, P2PConfig config) {
    this(self, positionManager, config, null);
  }

  public OverlaySelectorImpl(
      NodeDescriptor self,
      PositionManager positionManager,
      P2PConfig config,
      ShortcutStrategy shortcutStrategy) {
    this.self = Objects.requireNonNull(self);
    this.positionManager = Objects.requireNonNull(positionManager);
    this.config = Objects.requireNonNull(config);
    this.shortcutStrategy =
        shortcutStrategy == null ? createDefaultShortcutStrategy() : shortcutStrategy;
  }

  private ShortcutStrategy createDefaultShortcutStrategy() {
    String strategy = config.overlayShortcutStrategy();
    if (!"kleinberg".equals(strategy)) {
      return new RingShortcutStrategy();
    }
    return new KleinbergShortcutStrategy();
  }

  @Override
  public OverlaySelection select(String topic, List<NodeDescriptor> peers) {
    if (peers == null || peers.isEmpty()) return new OverlaySelection(List.of(), Set.of());

    log.debug(
        "OverlaySelector.select self={} topic={} peersIds={} peerCount={}",
        self.id(),
        topic,
        peers.stream().map(NodeDescriptor::id).toList(),
        peers.size());

    // Special-case: topology scan requests should reach all peers
    if (P2PTopics.TOPOLOGY_SCAN_REQUEST.equals(topic)) {
      return new OverlaySelection(new ArrayList<>(peers), Set.of());
    }

    // Position broadcasts must reach ALL peers so that vehicles outside the current overlay
    // can learn positions and later form geo-local neighbourships. Restricting this to
    // overlay neighbours creates a circular dependency: you need a position to become a
    // neighbour, but you only receive the position if you are already a neighbour.
    // if (P2PTopics.VEHICLE_POSITION.equals(topic)) {
    //  return new OverlaySelection(new ArrayList<>(peers), Set.of());
    // }

    if (self.role() == NodeRole.CLIENT && P2PTopics.RIDE_REQUEST.equals(topic)) {
      return new OverlaySelection(new ArrayList<>(peers), Set.of());
    }

    int minNeighbors = config.overlayMinNeighbors();
    int shortcuts = Math.max(0, config.overlayShortcuts());
    double maxDistance = config.overlayMaxDistance();

    List<NodeDescriptor> routingPeers = resolveRoutingPeers(peers, topic);
    log.debug(
        "OverlaySelector.select routingPeersIds={}",
        routingPeers.stream().map(NodeDescriptor::id).toList());
    boolean vehicleRelevant =
        self.role() == NodeRole.VEHICLE
            && (P2PTopics.RIDE_REQUEST.equals(topic)
                || P2PTopics.TOPOLOGY_SCAN_RESPONSE.equals(topic));

    int maxNeighbors = config.overlayMaxNeighbors();
    OverlaySelection smallWorld =
        distanceBoundOverlayPeers(
            routingPeers, minNeighbors, maxNeighbors, shortcuts, vehicleRelevant, maxDistance);
    List<NodeDescriptor> selected = smallWorld.peers();
    OverlaySelection out =
        new OverlaySelection(includeCollectorPeers(selected, peers), smallWorld.shortcutPeerIds());
    log.debug(
        "OverlaySelector.select resultPeersIds={}",
        out.peers().stream().map(NodeDescriptor::id).toList());
    return out;
  }

  private List<NodeDescriptor> resolveRoutingPeers(List<NodeDescriptor> peers, String topic) {
    if (P2PTopics.RIDE_REQUEST.equals(topic) || P2PTopics.TOPOLOGY_SCAN_RESPONSE.equals(topic)) {
      return peers.stream().filter(peer -> peer.role() == NodeRole.VEHICLE).toList();
    }
    return peers;
  }

  private List<NodeDescriptor> includeCollectorPeers(
      List<NodeDescriptor> selected, List<NodeDescriptor> allPeers) {
    List<NodeDescriptor> collectorPeers =
        allPeers.stream().filter(peer -> isCollectorNodeId(peer.id())).toList();
    if (collectorPeers.isEmpty()) return selected;
    Set<String> selectedIds = new HashSet<>(selected.size());
    selected.forEach(peer -> selectedIds.add(peer.id()));
    List<NodeDescriptor> result = new ArrayList<>(selected);
    for (NodeDescriptor collector : collectorPeers) {
      if (selectedIds.add(collector.id())) {
        result.add(collector);
      }
    }
    return result;
  }

  private boolean isCollectorNodeId(String nodeId) {
    if (nodeId == null || nodeId.isBlank()) return false;
    String configured = P2PRunContext.getProperty(P2PSystemProperties.OVERLAY_COLLECTOR_NODE_ID, "");
    if (!configured.isBlank() && configured.equals(nodeId)) return true;
    return nodeId.startsWith("sim-collector-");
  }

  private OverlaySelection distanceBoundOverlayPeers(
      List<NodeDescriptor> peers,
      int minNeighbors,
      int maxNeighbors,
      int shortcuts,
      boolean preferNearestVehicles,
      double maxDistance) {
    if (peers.isEmpty()) return new OverlaySelection(List.of(), Set.of());
    List<NodeDescriptor> sortedPeers =
        peers.stream().sorted(Comparator.comparing(NodeDescriptor::id)).toList();
    int startIndex = insertionIndex(sortedPeers, self.id());
    List<NodeDescriptor> selected = new ArrayList<>();
    Set<String> selectedIds = new HashSet<>();
    if (preferNearestVehicles) {
      // Pick all vehicles within maxDistance (up to maxNeighbors).
      // minNeighbors acts as a guaranteed-floor: if fewer are in range, fill up with the
      // absolute-nearest vehicles (regardless of distance) so the overlay stays connected
      // when nodes are sparse. Set minNeighbors=0 in the UI to disable this fallback
      // and get a pure distance-based neighbourhood (recommended for geo-local small-world).
      // If maxNeighbors is left at its default (Integer.MAX_VALUE) treat it as "unspecified"
      // and use minNeighbors as the effective in-range limit. This keeps behavior intuitive
      // for tests and typical usage where only a small neighborhood is desired unless a
      // specific max is configured.
      int limitInRange =
          (maxNeighbors == Integer.MAX_VALUE)
              ? Math.max(1, minNeighbors)
              : Math.max(1, maxNeighbors);
      List<NodeDescriptor> inRange =
          DistancePeerSelector.vehiclePeersWithinDistance(
              self, sortedPeers, positionManager, maxDistance, limitInRange, selectedIds);
      selected.addAll(inRange);
      inRange.forEach(peer -> selectedIds.add(peer.id()));
      if (minNeighbors > 0 && selected.size() < minNeighbors) {
        List<NodeDescriptor> nearestFallback =
            DistancePeerSelector.nearestVehiclePeers(
                self, sortedPeers, positionManager, minNeighbors - selected.size(), selectedIds);
        selected.addAll(nearestFallback);
        nearestFallback.forEach(peer -> selectedIds.add(peer.id()));
      }
    } else {
      int cap = Math.max(minNeighbors, maxNeighbors);
      for (int offset = 0; offset < sortedPeers.size() && selected.size() < cap; offset++) {
        NodeDescriptor peer = sortedPeers.get((startIndex + offset) % sortedPeers.size());
        if (selectedIds.add(peer.id())) selected.add(peer);
      }
    }

    int shortcutSlots = Math.max(0, shortcuts);
    if (shortcutSlots == 0) return new OverlaySelection(selected, Set.of());
    List<NodeDescriptor> shortcutsByStableHash =
        shortcutStrategy.selectShortcuts(
            self, sortedPeers, startIndex, selectedIds, shortcutSlots, positionManager);
    selected.addAll(shortcutsByStableHash);
    Set<String> shortcutPeerIds =
        shortcutsByStableHash.stream()
            .map(NodeDescriptor::id)
            .collect(java.util.stream.Collectors.toSet());
    return new OverlaySelection(selected, shortcutPeerIds);
  }

  private int insertionIndex(List<NodeDescriptor> sortedPeers, String selfId) {
    int low = 0;
    int high = sortedPeers.size();
    while (low < high) {
      int mid = (low + high) >>> 1;
      if (sortedPeers.get(mid).id().compareTo(selfId) < 0) low = mid + 1;
      else high = mid;
    }
    return low;
  }
}
