package de.sikeller.aqs.model;

public interface Client extends Entity {
    boolean isFinished();

    String getName();

    long getSpawnTime();

    ClientMode getMode();
}
