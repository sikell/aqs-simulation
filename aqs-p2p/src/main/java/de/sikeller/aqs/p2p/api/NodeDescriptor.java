package de.sikeller.aqs.p2p.api;

import java.util.Objects;

/**
 * Beschreibt einen Knoten im P2P-Netzwerk ueber seine eindeutige ID und Rolle.
 *
 * @param id technische Knoten-ID (muss gesetzt und nicht leer sein)
 * @param role Rolle des Knotens im Netz
 */
public record NodeDescriptor(String id, NodeRole role) {
  public NodeDescriptor {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(role, "role must not be null");
    if (id.isBlank()) {
      throw new IllegalArgumentException("id must not be blank");
    }
  }
}

