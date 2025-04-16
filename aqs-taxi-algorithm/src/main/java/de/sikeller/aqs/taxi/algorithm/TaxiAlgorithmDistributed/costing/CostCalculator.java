package de.sikeller.aqs.taxi.algorithm.TaxiAlgorithmDistributed.costing;

import de.sikeller.aqs.model.Client;
import de.sikeller.aqs.model.Taxi;
import java.util.Map;

/** Interface for calculating the marginal cost of adding a new client to a taxi's route. */
public interface CostCalculator {

  /**
   * Calculates the marginal cost (e.g., additional distance or time) incurred by a taxi if it were
   * to serve the new client, by finding the optimal insertion points for the client's pickup and
   * drop off into the taxi's existing route. The calculation must ensure that the total travel time
   * for the new client * (wait time + time in taxi) does not exceed the provided maxClientTripTime.
   *
   * @param taxi The taxi considering the new client.
   * @param newClient The new client requesting a ride.
   * @param maxClientTripTime The maximum permissible total travel time for the new client (e.g.,
   *     their walking time). Use Double.POSITIVE_INFINITY for no limit.
   * @return A CostCalculationResult object containing the calculated cost and the time taken for
   *     the calculation.
   */
  CostCalculationResult calculateMarginalCost(
      Taxi taxi, Client newClient, double maxClientTripTime);

  /**
   * Receives parameters from the simulation UI.
   *
   * @param parameters A map containing parameter names and their integer values.
   */
  void setParameters(Map<String, Integer> parameters);
}
