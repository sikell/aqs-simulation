package de.sikeller.aqs.model;

/** UI-Snapshot eines bekannten Knotens im P2P-Overlay. */
public record P2PNetworkNodeSnapshot(String id, String role, boolean localNode) {}

