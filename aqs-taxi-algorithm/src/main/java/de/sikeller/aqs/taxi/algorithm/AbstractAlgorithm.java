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
    return AlgorithmResult.builder().status(AlgorithmResult.Result.FOUND).build();
  }
}
