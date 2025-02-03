package de.sikeller.aqs.taxi.algorithm;

import de.sikeller.aqs.model.*;
import de.sikeller.aqs.model.AlgorithmResult;

import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TaxiAlgorithmGroupByTarget extends AbstractTaxiAlgorithm implements TaxiAlgorithm {

  private Map<String, Integer> parameters;

  public void setParameters(Map<String, Integer> parameters) {
    this.parameters = parameters;
  }

  @Override
  public SimulationConfiguration getParameters() {
    return new SimulationConfiguration("DetectionRadius");
  }

  @Override
  public AlgorithmResult nextStep(World world, Set<Client> waitingClients) {
    var nextClient = waitingClients.iterator().next();

    var taxiCandidates = getEmptyTaxis(world);
    if (taxiCandidates.isEmpty()) return stop("No taxis with capacity found.");

    var nearestTaxiWithCapacity =
        EntityUtils.findNearestNeighbor(nextClient.getPosition(), taxiCandidates);
    if (nearestTaxiWithCapacity == null) return fail("No nearest taxi found.");

    Taxi nearestTaxi = nearestTaxiWithCapacity.v1();

    planClientForTaxi(nearestTaxi, nextClient, TargetList.mergeOrders);
    log.debug("Taxi {} plan client {}", nearestTaxi.getName(), nextClient.getName());

    // search for others with same target and start
    var clientsOrderedBySameTarget =
        EntityUtils.sortByNearest(nextClient.getTarget(), waitingClients, Entity::getTarget);
    for (Tuple<Client, Double> otherClient : clientsOrderedBySameTarget) {
      if (!nearestTaxi.hasCapacity()) {
        break;
      }
      if (!otherClient.v1().equals(nextClient)
          && otherClient.v2() < parameters.get("DetectionRadius")
          && nextClient.getPosition().distance(otherClient.v1().getPosition()) < parameters.get("DetectionRadius")) {
        Client client = otherClient.v1();
        planClientForTaxi(nearestTaxi, client, TargetList.mergeOrders);
      }
    }

    return ok();
  }
}
