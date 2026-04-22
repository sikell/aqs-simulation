package de.sikeller.aqs.p2p.service.position;

/**
 * Simple immutable position record for P2P vehicle positions.
 */
public record Position(int x,int y,long tick) {
}

