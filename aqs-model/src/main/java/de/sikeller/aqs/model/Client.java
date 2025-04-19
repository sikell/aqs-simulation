package de.sikeller.aqs.model;

public interface Client extends Entity {
    boolean isSpawned(long currentTime);

    boolean isMoving();

    boolean isFinished();

    String getName();

    long getSpawnTime();

    ClientMode getMode();

    Position getPosition();

    Position getTarget();

    long getLastUpdate();

    double getCurrentSpeed();
}
