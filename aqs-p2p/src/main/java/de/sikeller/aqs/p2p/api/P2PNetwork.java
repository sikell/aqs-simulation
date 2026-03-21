package de.sikeller.aqs.p2p.api;

import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Abstraktion fuer den Transport eines P2P-Netzes (z. B. In-Memory, LAN).
 *
 * <p>Das Interface trennt Nachrichtenrouting von fachlicher Service-Logik.
 */
public interface P2PNetwork {
  /** Meldet einen lokalen Knoten am Netz an und registriert den Message-Handler. */
  void join(NodeDescriptor node, Consumer<P2PMessage> messageHandler);

  /** Meldet einen Knoten anhand seiner ID vom Netz ab. */
  void leave(String nodeId);

  /** Sendet eine Nachricht gezielt an einen einzelnen Zielknoten. */
  void sendTo(String targetNodeId, P2PMessage message);

  /** Sendet eine Nachricht an alle Peers, die den Ziel-Filter erfuellen. */
  void broadcast(P2PMessage message, Predicate<NodeDescriptor> targetFilter);

  /** Liefert den aktuell bekannten Peer-Snapshot. */
  Set<NodeDescriptor> peers();
}

