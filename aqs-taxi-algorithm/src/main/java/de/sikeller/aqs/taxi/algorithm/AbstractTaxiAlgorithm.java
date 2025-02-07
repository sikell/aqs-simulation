package de.sikeller.aqs.taxi.algorithm;

import static de.sikeller.aqs.model.ClientMode.WAITING;

import de.sikeller.aqs.model.*;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public abstract class AbstractTaxiAlgorithm extends AbstractAlgorithm {

  protected abstract AlgorithmResult nextStep(World world, Set<Client> waitingClients);

  @Override
  public AlgorithmResult nextStep(World world) {
    var waitingClients = getWaitingClients(world);
    if (waitingClients.isEmpty()) return stop("No clients waiting for a taxi.");
    return nextStep(world, waitingClients);
  }

  protected Set<Client> getWaitingClients(World world) {
    return world.getSpawnedClients().stream()
        .filter(client -> WAITING.equals(client.getMode()))
        .collect(Collectors.toSet());
  }

  protected Set<Taxi> getTaxisWithCapacity(World world) {
    return world.getTaxis().stream().filter(Taxi::hasCapacity).collect(Collectors.toSet());
  }

  protected Set<Taxi> getEmptyTaxis(World world) {
    return world.getTaxis().stream().filter(Taxi::isEmpty).collect(Collectors.toSet());
  }

  protected void planClientForTaxi(
      Taxi taxi, Client client, Function<List<Order>, List<Position>> flattenFunction) {
    taxi.planClient(client);
    taxi.addOrder(Order.of(client.getPosition(), client.getTarget()), flattenFunction);
  }
}
