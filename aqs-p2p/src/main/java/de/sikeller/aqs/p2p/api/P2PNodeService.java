package de.sikeller.aqs.p2p.api;

/** Lebenszyklus- und Publish-API fuer einen lokalen P2P-Knotenservice. */
public interface P2PNodeService extends AutoCloseable {
  /** Liefert die statische Beschreibung dieses Knotens (ID/Rolle). */
  NodeDescriptor descriptor();

  /** Startet den Service und verbindet ihn mit dem konfigurierten Netzwerk. */
  void start();

  /** Stoppt den Service und trennt ihn vom Netzwerk. */
  void stop();

  /** Publiziert eine Nachricht auf einem Topic an passende Zielknoten. */
  void publish(String topic, String payload);

  @Override
  default void close() {
    stop();
  }
}
