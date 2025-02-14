package de.sikeller.aqs.taxi.algorithm;

import de.sikeller.aqs.model.*;
import de.sikeller.aqs.model.AlgorithmResult;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Getter
public class TaxiAlgorithmFillRandomSeats extends AbstractTaxiAlgorithm implements TaxiAlgorithm {

  private Map<String, Integer> parameters;
  private final String name = "FillRandomSeats";
  private int counter = 0;

  public void setParameters(Map<String, Integer> parameters) {
    this.parameters = parameters;
  }

  @Override
  public SimulationConfiguration getParameters() {
    return new SimulationConfiguration("SeatRandomizationSeed");
  }

  @Override
  public AlgorithmResult nextStep(World world, Set<Client> waitingClients) {
    var taxiCandidates = getTaxisWithCapacity(world);
    if (taxiCandidates.isEmpty()) return stop("No taxis with capacity found.");
    var nextTaxi = taxiCandidates.iterator().next();
    var capacity = (int) (new Random(parameters.get("SeatRandomizationSeed")).nextDouble()  * nextTaxi.getCurrentCapacity() + 1);
    System.out.println(new Random(parameters.get("SeatRandomizationSeed") + counter++).nextDouble());
    var nearestClients = EntityUtils.sortByNearest(nextTaxi.getPosition(), waitingClients);

    for (int taxiSeat = 0; taxiSeat < capacity && taxiSeat < nearestClients.size(); taxiSeat++) {
      var nextClient = nearestClients.get(taxiSeat).v1();
      planClientForTaxi(nextTaxi, nextClient, TargetList.mergeOrders);
      log.debug("Taxi {} with {} seats to fill plan client {}", nextTaxi.getName(), capacity, nextClient.getName());
    }

    return ok();
  }
}
