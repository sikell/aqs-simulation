package de.sikeller.aqs.taxi.algorithm.collector;

import de.sikeller.aqs.model.P2PNetworkEdgeSnapshot;
import de.sikeller.aqs.model.P2PNetworkNodeSnapshot;
import de.sikeller.aqs.model.P2PNetworkSnapshot;
import de.sikeller.aqs.p2p.api.NodeDescriptor;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** Builds immutable UI snapshots of the current overlay view. */
public final class P2PNetworkSnapshotBuilder {
  private static final String UNKNOWN_ROLE = "UNKNOWN";
  private static final String EDGE_KEY_SEPARATOR = "\u0000";

  public P2PNetworkSnapshot build(
      String localNodeId,
      String localRole,
      Collection<NodeDescriptor> peers,
      Map<String, TopologyViewData> topologyViewsByNodeId) {
    return build(localNodeId, localRole, peers, topologyViewsByNodeId, Map.of());
  }

  public P2PNetworkSnapshot build(
      String localNodeId,
      String localRole,
      Collection<NodeDescriptor> peers,
      Map<String, TopologyViewData> topologyViewsByNodeId,
      Map<String, String> nodeAliases) {
    Map<String, String> aliases = nodeAliases == null ? Map.of() : nodeAliases;
    String displayLocalNodeId = alias(localNodeId, aliases);
    Map<String, NodeDescriptor> peerById =
        peers == null
            ? Map.of()
            : peers.stream()
                .map(peer -> new NodeDescriptor(alias(peer.id(), aliases), peer.role()))
                .collect(Collectors.toMap(NodeDescriptor::id, p -> p, (left, right) -> left));
    Map<String, TopologyViewData> views =
        topologyViewsByNodeId == null
            ? Map.of()
            : topologyViewsByNodeId.values().stream()
                .map(view -> alias(view, aliases))
                .collect(
                    Collectors.toMap(
                        TopologyViewData::nodeId, view -> view, (left, right) -> left));

    TreeSet<String> nodeIds = new TreeSet<>(peerById.keySet());
    if (displayLocalNodeId != null && !displayLocalNodeId.isBlank()) {
      nodeIds.add(displayLocalNodeId);
    }
    nodeIds.addAll(views.keySet());
    views.values().forEach(view -> nodeIds.addAll(view.neighborIds()));

    List<P2PNetworkNodeSnapshot> nodes =
        nodeIds.stream()
            .map(
                nodeId ->
                    new P2PNetworkNodeSnapshot(
                        nodeId,
                        resolveRole(nodeId, displayLocalNodeId, localRole, peerById, views),
                        nodeId.equals(displayLocalNodeId)))
            .collect(Collectors.toList());

    Map<String, Boolean> shortcutByEdgeKey = new HashMap<>();
    for (TopologyViewData view : views.values()) {
      for (String neighborId : view.neighborIds()) {
        String key = edgeKey(view.nodeId(), neighborId);
        if (key.isBlank()) {
          continue;
        }
        boolean shortcut = view.shortcutNeighborIds().contains(neighborId);
        shortcutByEdgeKey.merge(key, shortcut, (existing, candidate) -> existing || candidate);
      }
    }

    List<P2PNetworkEdgeSnapshot> edges =
        shortcutByEdgeKey.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(
                entry -> {
                  String edgeKey = entry.getKey();
                  int separator = edgeKey.indexOf(EDGE_KEY_SEPARATOR);
                  if (separator < 1 || separator >= edgeKey.length() - 1) {
                    return null;
                  }
                  String from = edgeKey.substring(0, separator);
                  String to = edgeKey.substring(separator + 1);
                  return new P2PNetworkEdgeSnapshot(from, to, entry.getValue());
                })
            .filter(Objects::nonNull)
            .collect(Collectors.toList());

    return new P2PNetworkSnapshot(
        displayLocalNodeId == null ? "" : displayLocalNodeId, nodes, edges);
  }

  private String resolveRole(
      String nodeId,
      String localNodeId,
      String localRole,
      Map<String, NodeDescriptor> peerById,
      Map<String, TopologyViewData> views) {
    if (nodeId.equals(localNodeId)) {
      return localRole == null || localRole.isBlank() ? UNKNOWN_ROLE : localRole;
    }

    NodeDescriptor peer = peerById.get(nodeId);
    if (peer != null) {
      return peer.role().name();
    }

    TopologyViewData view = views.get(nodeId);
    if (view != null && view.role() != null && !view.role().isBlank()) {
      return view.role();
    }
    return UNKNOWN_ROLE;
  }

  private String edgeKey(String leftNodeId, String rightNodeId) {
    if (leftNodeId == null || rightNodeId == null || leftNodeId.equals(rightNodeId)) {
      return "";
    }
    return leftNodeId.compareTo(rightNodeId) < 0
        ? leftNodeId + EDGE_KEY_SEPARATOR + rightNodeId
        : rightNodeId + EDGE_KEY_SEPARATOR + leftNodeId;
  }

  private TopologyViewData alias(TopologyViewData view, Map<String, String> aliases) {
    return new TopologyViewData(
        alias(view.nodeId(), aliases),
        view.role(),
        view.neighborIds().stream().map(id -> alias(id, aliases)).collect(Collectors.toSet()),
        view.shortcutNeighborIds().stream()
            .map(id -> alias(id, aliases))
            .collect(Collectors.toSet()));
  }

  private String alias(String nodeId, Map<String, String> aliases) {
    if (nodeId == null) {
      return null;
    }
    return aliases.getOrDefault(nodeId, nodeId);
  }

  public record TopologyViewData(
      String nodeId, String role, Set<String> neighborIds, Set<String> shortcutNeighborIds) {
    public TopologyViewData {
      neighborIds = neighborIds == null ? Set.of() : Set.copyOf(neighborIds);
      shortcutNeighborIds =
          shortcutNeighborIds == null
              ? Set.of()
              : shortcutNeighborIds.stream()
                  .filter(neighborIds::contains)
                  .collect(Collectors.toSet());
    }
  }
}

