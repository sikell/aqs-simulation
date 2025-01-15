package de.sikeller.aqs.taxi.algorithm;

import static de.sikeller.aqs.model.ClientMode.WAITING;

import de.sikeller.aqs.model.*;

import java.util.Set;
import java.util.stream.Collectors;

public abstract class AbstractTaxiAlgorithm implements TaxiAlgorithm {
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

  protected Set<Client> getWaitingClients(World world) {
    return world.getClients().stream()
        .filter(client -> WAITING.equals(client.getMode()))
        .collect(Collectors.toSet());
  }

  protected Set<Taxi> getTaxisWithCapacity(World world) {
    return world.getTaxis().stream().filter(Taxi::hasCapacity).collect(Collectors.toSet());
  }

  protected Set<Taxi> getEmptyTaxis(World world) {
    return world.getTaxis().stream().filter(Taxi::isEmpty).collect(Collectors.toSet());
  }
}
