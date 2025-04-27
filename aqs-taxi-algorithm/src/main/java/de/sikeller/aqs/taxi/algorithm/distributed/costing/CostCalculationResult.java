package de.sikeller.aqs.taxi.algorithm.distributed.costing;

import de.sikeller.aqs.model.OrderNode;
import java.util.List;

/**
 * Holds the result of a cost calculation. Includes the calculated marginal cost and the time taken
 * for the calculation.
 */
public record CostCalculationResult(
    double cost,
    long calculationTimeNanos,
    List<OrderNode> optimalRoute /*Null if infeasible*/ ) {
  /** Constant representing an infeasible result. */
  public static final double INFEASIBLE_COST = Double.POSITIVE_INFINITY;
}
