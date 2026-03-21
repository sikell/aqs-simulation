package de.sikeller.aqs.p2p.api;

/**
 * Laufzeit-Snapshot eines lokalen P2P-Knotens fuer Monitoring/Logging.
 *
 * @param nodeId Knoten-ID
 * @param role Knotenrolle
 * @param running true, wenn der Service aktiv mit dem Netz verbunden ist
 * @param knownPeers Anzahl aktuell bekannter Peers
 * @param inboxSize Anzahl empfangener Nachrichten im lokalen Inbox-Snapshot
 * @param messagesSent Anzahl gesendeter Nachrichten seit Start
 * @param messagesReceived Anzahl empfangener Nachrichten seit Start
 */
public record NodeRuntimeStatus(
    String nodeId,
    NodeRole role,
    boolean running,
    int knownPeers,
    int inboxSize,
    long messagesSent,
    long messagesReceived) {

  /** Liefert eine kompakte, gut logbare Einzeilen-Darstellung. */
  public String asLogLine() {
    return String.format(
        "node=%s role=%s running=%s peers=%d inbox=%d tx=%d rx=%d",
        nodeId, role, running, knownPeers, inboxSize, messagesSent, messagesReceived);
  }
}

