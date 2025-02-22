package de.sikeller.aqs.taxi.algorithm;

import de.sikeller.aqs.model.*;
import de.sikeller.aqs.model.AlgorithmResult;

import java.util.Map;
import java.util.Set;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Getter
public class TaxiAlgorithmSinglePassenger extends AbstractTaxiAlgorithm implements TaxiAlgorithm {

  private Map<String, Integer> parameters;
  private final String name = "SinglePassenger";
  public void setParameters(Map<String, Integer> parameters) {
    this.parameters = parameters;
  }

  @Override
  public SimulationConfiguration getParameters() {
    return new SimulationConfiguration();
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

    planClientForTaxi(nearestTaxi, nextClient, world.getCurrentTime(), TargetList.sequentialOrders);
    log.debug("Taxi {} plan client {}", nearestTaxi.getName(), nextClient.getName());

    return ok();
  }
}
