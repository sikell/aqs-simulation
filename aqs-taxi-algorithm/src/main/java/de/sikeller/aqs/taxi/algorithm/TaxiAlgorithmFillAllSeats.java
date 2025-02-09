package de.sikeller.aqs.taxi.algorithm;

import de.sikeller.aqs.model.*;
import de.sikeller.aqs.model.AlgorithmResult;

import java.util.Map;
import java.util.Set;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Getter
public class TaxiAlgorithmFillAllSeats extends AbstractTaxiAlgorithm implements TaxiAlgorithm {

  private Map<String, Integer> parameters;
  private final String name = "FillAllSeats";

  public void setParameters(Map<String, Integer> parameters) {
    this.parameters = parameters;
  }

  @Override
  public SimulationConfiguration getParameters() {
    return new SimulationConfiguration("DetectionRadius");
  }

  @Override
  public AlgorithmResult nextStep(World world, Set<Client> waitingClients) {
    var taxiCandidates = getTaxisWithCapacity(world);
    if (taxiCandidates.isEmpty()) return stop("No taxis with capacity found.");
    var nextTaxi = taxiCandidates.iterator().next();
    var capacity = nextTaxi.getCurrentCapacity();
    var nearestClients = EntityUtils.sortByNearest(nextTaxi.getPosition(), waitingClients);

    for (int taxiSeat = 0; taxiSeat < capacity && taxiSeat < nearestClients.size(); taxiSeat++) {
      var nextClient = nearestClients.get(taxiSeat).v1();
      planClientForTaxi(nextTaxi, nextClient, TargetList.mergeOrders);
      log.debug("Taxi {} plan client {}", nextTaxi.getName(), nextClient.getName());
    }

    return ok();
  }
}
