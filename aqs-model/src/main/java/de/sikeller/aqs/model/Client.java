package de.sikeller.aqs.model;

public interface Client extends Entity {
    boolean isWaiting();

    boolean isFinished();

    String getName();

    long getSpawnTime();

    ClientMode getMode();
}
