package de.sikeller.aqs.p2p.api;

/** Definiert die fachliche Rolle eines P2P-Knotens. */
public enum NodeRole {
  /** Knoten, der Fahranfragen stellt und Antworten sammelt. */
  CLIENT,
  /** Knoten, der Angebote fuer Fahranfragen liefert. */
  VEHICLE,
  /** Generischer/technischer Knoten ohne konkrete Client- oder Vehicle-Rolle. */
  NODE
}

