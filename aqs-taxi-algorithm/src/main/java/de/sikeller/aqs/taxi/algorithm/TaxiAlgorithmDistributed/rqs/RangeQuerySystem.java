package de.sikeller.aqs.taxi.algorithm.TaxiAlgorithmDistributed.rqs;

import java.util.Map;
import java.util.Set;

import de.sikeller.aqs.model.*;

/**
 * Interface for the Range Query System. It finds taxis that are relevant for a client's ride
 * request based on idle Taxi proximity or route overlap.
 */
public interface RangeQuerySystem {

  /**
   * Finds taxis within a specified range relevant to a client's requested route.
   *
   * @param world The current state of the simulation world.
   * @param clientStart The current (starting) position of the client's request.
   * @param clientTarget The target position of the client's request.
   * @param searchRadius The radius around the client's route or start position to search within.
   * @return A set of candidate Taxi objects. Returns an empty set if no taxis are found.
   */
  Set<Taxi> findTaxisInRange(
      World world, Position clientStart, Position clientTarget, double searchRadius);

  /**
   * Receives parameters from the simulation UI.
   *
   * @param parameters A map containing parameter names and their integer values.
   */
  void setParameters(Map<String, Integer> parameters);
}
