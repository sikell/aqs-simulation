package de.sikeller.aqs.p2p.api;

import java.time.Instant;
import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Transportobjekt fuer P2P-Nachrichten inkl. Schema- und Korrelationsmetadaten.
 *
 * @param schemaVersion Version des Wire-Schemas
 * @param requestId fachliche Request-ID (z. B. pro Ride-Request)
 * @param correlationId Korrelation innerhalb eines Flows (z. B. requestId bei Accept/Commit)
 * @param senderId ID des sendenden Knotens
 * @param topic Themenkanal der Nachricht
 * @param payload serialisierter Nachrichteninhalt
 * @param timestamp Erzeugungszeitpunkt der Nachricht
 */
public record P2PMessage(
    int schemaVersion,
    String requestId,
    String correlationId,
    String senderId,
    String topic,
    String payload,
    Instant timestamp)
    implements Serializable {
  @Serial private static final long serialVersionUID = 1L;

  public static final int SCHEMA_VERSION = 1;

  public P2PMessage {
    if (schemaVersion <= 0) {
      throw new IllegalArgumentException("schemaVersion must be greater than 0");
    }
    Objects.requireNonNull(requestId, "requestId must not be null");
    Objects.requireNonNull(correlationId, "correlationId must not be null");
    Objects.requireNonNull(senderId, "senderId must not be null");
    Objects.requireNonNull(topic, "topic must not be null");
    Objects.requireNonNull(payload, "payload must not be null");
    Objects.requireNonNull(timestamp, "timestamp must not be null");
  }

  /** Erstellt eine neue Nachricht mit automatisch erzeugter requestId. */
  public static P2PMessage now(String senderId, String topic, String payload) {
    return new P2PMessage(
        SCHEMA_VERSION, UUID.randomUUID().toString(), "", senderId, topic, payload, Instant.now());
  }

  /**
   * Erstellt eine neue Nachricht mit expliziter requestId/correlationId.
   *
   * <p>Wird fuer mehrstufige Protokolle (Request -> Offer -> Accept -> Commit) genutzt.
   */
  public static P2PMessage now(
      String senderId, String topic, String payload, String requestId, String correlationId) {
    return new P2PMessage(
        SCHEMA_VERSION, requestId, correlationId, senderId, topic, payload, Instant.now());
  }
}

