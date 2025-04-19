package de.sikeller.aqs.model;

/**
 * This interface contains ALL allowed methods to mutate the simulation world. Each action here can
 * be safely used by any taxi algorithm. You should never mutate the simulation world without using
 * this interface!
 */
public interface WorldMutator {
  /**
   * Plan a client for a taxi to be picked up. The taxi automatically picks up the client by the
   * planned order flatten function.
   *
   * @param taxi the taxi to be planned to pick up a client
   * @param client the client to be picked up
   * @param flattenFunction the function which defines the order when this client is picked up
   */
  void planClientForTaxi(Taxi taxi, Client client, OrderFlattenFunction flattenFunction);

  /**
   * Plan a path to fulfill all taxi orders. You can pass an ordering function to sort / reorder the
   * order nodes to result in a taxi path.
   *
   * @param taxi to be planned
   * @param flattenFunction the function to reorder the order path
   */
  void planOrderPath(Taxi taxi, OrderFlattenFunction flattenFunction);

  /**
   * Fully clear a taxi. Removes all contained and planned clients and clears the order path.
   *
   * @param taxi to be cleared
   */
  void clearTaxi(Taxi taxi);
}
