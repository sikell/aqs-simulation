package de.sikeller.aqs.model;

/** UI-Snapshot einer (ungerichteten) Verbindung zwischen zwei P2P-Knoten. */
public record P2PNetworkEdgeSnapshot(String fromNodeId, String toNodeId, boolean shortcut) {

  public P2PNetworkEdgeSnapshot(String fromNodeId, String toNodeId) {
    this(fromNodeId, toNodeId, false);
  }
}

