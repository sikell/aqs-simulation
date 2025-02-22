package de.sikeller.aqs.taxi.algorithm;

import static de.sikeller.aqs.model.ClientMode.WAITING;

import de.sikeller.aqs.model.*;
import java.util.Set;
import java.util.stream.Collectors;

public abstract class AbstractTaxiAlgorithm extends AbstractAlgorithm {

  protected abstract AlgorithmResult nextStep(World world, Set<Client> waitingClients);

  @Override
  public AlgorithmResult nextStep(World world) {
    var waitingClients = getClientsByModes(world, Set.of(WAITING));
    if (waitingClients.isEmpty()) return stop("No clients waiting for a taxi.");
    return nextStep(world, waitingClients);
  }

  protected Set<Client> getClientsByModes(World world, Set<ClientMode> modes) {
    return world.getSpawnedClients().stream()
        .filter(client -> modes.contains(client.getMode()))
        .collect(Collectors.toSet());
  }

  protected Set<Taxi> getTaxisWithCapacity(World world) {
    return world.getTaxis().stream().filter(Taxi::hasCapacity).collect(Collectors.toSet());
  }

  protected Set<Taxi> getEmptyTaxis(World world) {
    return world.getTaxis().stream().filter(Taxi::isEmpty).collect(Collectors.toSet());
  }

  protected void planOrderPath(Taxi taxi, OrderFlattenFunction flattenFunction) {
    taxi.planOrderPath(flattenFunction);
  }

  protected void planClientForTaxi(
      Taxi taxi, Client client, long currentTime, OrderFlattenFunction flattenFunction) {
    taxi.planClient(client);
    taxi.addOrder(Order.of(client, currentTime), flattenFunction);
  }
}
