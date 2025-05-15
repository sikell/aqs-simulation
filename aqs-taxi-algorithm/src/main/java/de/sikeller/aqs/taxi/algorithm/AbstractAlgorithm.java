package de.sikeller.aqs.taxi.algorithm;

import de.sikeller.aqs.model.*;

public abstract class AbstractAlgorithm implements TaxiAlgorithm {
  protected AlgorithmResult fail(String message) {
    return AlgorithmResult.builder()
        .status(AlgorithmResult.Result.EXCEPTION)
        .message(message)
        .build();
  }

  protected AlgorithmResult stop(String message) {
    return AlgorithmResult.builder().status(AlgorithmResult.Result.STOP).message(message).build();
  }

  protected AlgorithmResult ok() {
    return ok(null);
  }

  protected AlgorithmResult ok(Long calculationTime) {
    return AlgorithmResult.builder()
        .status(AlgorithmResult.Result.FOUND)
        .calculationTime(calculationTime)
        .build();
  }
}
