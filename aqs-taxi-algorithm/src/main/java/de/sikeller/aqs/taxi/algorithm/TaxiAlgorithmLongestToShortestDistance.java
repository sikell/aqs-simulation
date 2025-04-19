package de.sikeller.aqs.taxi.algorithm;

import de.sikeller.aqs.model.*;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

@Slf4j
@Getter
public class TaxiAlgorithmLongestToShortestDistance extends AbstractTaxiAlgorithm
    implements TaxiAlgorithm {

  @Setter private Map<String, Integer> parameters;
  private final String name = "LongestToShortestDistance";

  @Override
  public SimulationConfiguration getParameters() {
    return new SimulationConfiguration();
  }

  @Override
  public AlgorithmResult nextStep(World world, Set<Client> waitingClients) {
    var nextClient = waitingClients.iterator().next();
    for (Client client : waitingClients) {
      double distanceNextClient = nextClient.getPosition().distance(nextClient.getTarget());
      double distanceNewClient = client.getPosition().distance(client.getTarget());
      if (distanceNewClient > distanceNextClient) nextClient = client;
    }
    //        var nextClient = distanceTracker.;
    var taxiCanidates = getEmptyTaxis(world);
    if (taxiCanidates.isEmpty()) {
      return stop("No taxis with capacity found");
    }

    var nearestTaxiWithCapacity =
        EntityUtils.findNearestNeighbor(nextClient.getPosition(), taxiCanidates);
    if (nearestTaxiWithCapacity == null) {
      return fail("No nearest taxi found");
    }

    Taxi nearestTaxi = nearestTaxiWithCapacity.v1();
    world.mutate().planClientForTaxi(nearestTaxi, nextClient, TargetList.sequentialOrders);
    log.debug("Taxi {} plan client {}", nearestTaxi.getName(), nextClient.getName());

    return ok();
  }
}
