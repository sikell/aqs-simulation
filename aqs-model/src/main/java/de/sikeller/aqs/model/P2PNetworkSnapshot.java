package de.sikeller.aqs.model;

import java.util.List;

/** UI-Snapshot fuer eine einfache Darstellung des lokalen P2P-Peer-Graphs. */
public record P2PNetworkSnapshot(
    String localNodeId,
    List<P2PNetworkNodeSnapshot> nodes,
    List<P2PNetworkEdgeSnapshot> edges) {

  public P2PNetworkSnapshot {
    nodes = nodes == null ? List.of() : List.copyOf(nodes);
    edges = edges == null ? List.of() : List.copyOf(edges);
  }

  public static P2PNetworkSnapshot empty() {
    return new P2PNetworkSnapshot("", List.of(), List.of());
  }
}

