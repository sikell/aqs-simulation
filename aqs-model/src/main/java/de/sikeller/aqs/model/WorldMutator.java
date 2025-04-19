package de.sikeller.aqs.model;

public interface WorldMutator {
    void planClientForTaxi(
            Taxi taxi, Client client, OrderFlattenFunction flattenFunction);

    void planOrderPath(Taxi taxi, OrderFlattenFunction flattenFunction);
}
