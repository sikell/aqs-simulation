package de.sikeller.aqs.taxi.algorithm.distributed.costing;

/**
 * Holds the result of a cost calculation. Includes the calculated marginal cost and the time taken
 * for the calculation.
 */
public record CostCalculationResult(
    double cost,
    long calculationTimeNanos) {
  /** Constant representing an infeasible result. */
  public static final double INFEASIBLE_COST = Double.POSITIVE_INFINITY;
}
