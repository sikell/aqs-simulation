package de.sikeller.aqs.p2p.service.overlay;

import de.sikeller.aqs.model.Position;
import de.sikeller.aqs.p2p.api.NodeDescriptor;
import de.sikeller.aqs.p2p.api.NodeRole;
import de.sikeller.aqs.p2p.service.position.PositionManager;
import de.sikeller.aqs.p2p.util.P2PGeoUtils;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

public final class DistancePeerSelector {
  private DistancePeerSelector() {}

  public static List<NodeDescriptor> vehiclePeersWithinDistance(
      NodeDescriptor self,
      List<NodeDescriptor> sortedPeers,
      PositionManager positionManager,
      double maxDistance,
      int limit,
      Set<String> excludedPeerIds) {
    if (self == null || sortedPeers == null || positionManager == null) return List.of();
    if (maxDistance < 0) return List.of();
    Map<String, Position> positionsSnapshot = positionManager.snapshotPositions();
    Position selfPos = positionsSnapshot.get(self.id());
    if (selfPos == null) return List.of();
    if (limit <= 0) {
      return sortedPeers.stream()
          .filter(peer -> peer.role() == NodeRole.VEHICLE)
          .filter(
              peer -> {
                Position other = positionsSnapshot.get(peer.id());
                return other != null
                    && P2PGeoUtils.distance(
                            selfPos.getX(), selfPos.getY(), other.getX(), other.getY())
                        <= maxDistance;
              })
          .sorted(
              Comparator.comparingDouble(
                      (NodeDescriptor peer) ->
                          P2PGeoUtils.distance(
                              selfPos.getX(),
                              selfPos.getY(),
                              positionsSnapshot.get(peer.id()).getX(),
                              positionsSnapshot.get(peer.id()).getY()))
                  .thenComparing(NodeDescriptor::id))
          .toList();
    }

    // Pre-compute distances to avoid repeated map lookups inside the heap comparator
    Map<String, Double> distCache = new HashMap<>();

    // Use comparator-only constructor to avoid allocating a giant internal array when
    // `limit` may be very large (e.g. Integer.MAX_VALUE). The heap will still be
    // constrained logically by `limit` while avoiding an initial memory spike.
    PriorityQueue<NodeDescriptor> heap =
        new PriorityQueue<>(
            (a, b) -> {
              double da =
                  distCache.computeIfAbsent(
                      a.id(),
                      id ->
                          P2PGeoUtils.distance(
                              selfPos.getX(),
                              selfPos.getY(),
                              positionsSnapshot.get(id).getX(),
                              positionsSnapshot.get(id).getY()));
              double db =
                  distCache.computeIfAbsent(
                      b.id(),
                      id ->
                          P2PGeoUtils.distance(
                              selfPos.getX(),
                              selfPos.getY(),
                              positionsSnapshot.get(id).getX(),
                              positionsSnapshot.get(id).getY()));
              int cmp = Double.compare(db, da);
              if (cmp != 0) return cmp;
              return b.id().compareTo(a.id());
            });

    for (NodeDescriptor peer : sortedPeers) {
      if (peer.role() != NodeRole.VEHICLE) continue;
      if (excludedPeerIds != null && excludedPeerIds.contains(peer.id())) continue;
      Position other = positionsSnapshot.get(peer.id());
      if (other == null) continue;
      double d =
          distCache.computeIfAbsent(
              peer.id(),
              id ->
                  P2PGeoUtils.distance(selfPos.getX(), selfPos.getY(), other.getX(), other.getY()));
      if (d > maxDistance) continue;
      if (heap.size() < limit) heap.add(peer);
      else {
        NodeDescriptor worst = heap.peek();
        double worstD =
            distCache.computeIfAbsent(
                worst.id(),
                id ->
                    P2PGeoUtils.distance(
                        selfPos.getX(),
                        selfPos.getY(),
                        positionsSnapshot.get(id).getX(),
                        positionsSnapshot.get(id).getY()));
        if (d < worstD || (d == worstD && peer.id().compareTo(worst.id()) < 0)) {
          heap.poll();
          heap.add(peer);
        }
      }
    }

    List<NodeDescriptor> result = new ArrayList<>(heap);
    result.sort(
        Comparator.comparingDouble(
                (NodeDescriptor peer) ->
                    distCache.computeIfAbsent(
                        peer.id(),
                        id ->
                            P2PGeoUtils.distance(
                                selfPos.getX(),
                                selfPos.getY(),
                                positionsSnapshot.get(id).getX(),
                                positionsSnapshot.get(id).getY())))
            .thenComparing(NodeDescriptor::id));
    return result;
  }

  public static List<NodeDescriptor> nearestVehiclePeers(
      NodeDescriptor self,
      List<NodeDescriptor> sortedPeers,
      PositionManager positionManager,
      int localSlots,
      Set<String> excludedPeerIds) {
    if (self == null || sortedPeers == null || positionManager == null) return List.of();
    if (localSlots <= 0) return List.of();
    Map<String, Position> positionsSnapshot = positionManager.snapshotPositions();
    Position selfPos = positionsSnapshot.get(self.id());
    if (selfPos == null) return List.of();
    return sortedPeers.stream()
        .filter(peer -> peer.role() == NodeRole.VEHICLE)
        .filter(peer -> excludedPeerIds == null || !excludedPeerIds.contains(peer.id()))
        .filter(
            peer -> positionsSnapshot.get(peer.id()) != null) // skip peers with unknown position
        .sorted(
            Comparator.comparingDouble(
                    (NodeDescriptor peer) ->
                        P2PGeoUtils.distance(
                            selfPos.getX(),
                            selfPos.getY(),
                            positionsSnapshot.get(peer.id()).getX(),
                            positionsSnapshot.get(peer.id()).getY()))
                .thenComparing(NodeDescriptor::id))
        .limit(localSlots)
        .toList();
  }
}
