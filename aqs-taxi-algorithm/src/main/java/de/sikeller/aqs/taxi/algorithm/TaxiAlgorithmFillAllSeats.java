package de.sikeller.aqs.taxi.algorithm;

import static de.sikeller.aqs.model.ClientMode.MOVING;

import de.sikeller.aqs.model.*;
import de.sikeller.aqs.model.AlgorithmResult;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Getter
public class TaxiAlgorithmFillAllSeats extends AbstractTaxiAlgorithm implements TaxiAlgorithm {

  @Override
  public SimulationConfiguration getParameters() {
    return new SimulationConfiguration("SeatLimit", "DetectionRadius");
  }

  @Override
  public AlgorithmResult nextStep(World world) {
    var waitingClients = getWaitingClients(world);
    if (waitingClients.isEmpty()) return stop("No clients waiting for a taxi.");

    var taxiCandidates = getTaxisWithCapacity(world);
    if (taxiCandidates.isEmpty()) return stop("No taxis with capacity found.");
    var nextTaxi = taxiCandidates.iterator().next();
    var capacity = nextTaxi.getCurrentCapacity();
    var nearestClients = EntityUtils.sortByNearest(nextTaxi.getPosition(), waitingClients);

    for (int taxiSeat = 0; taxiSeat < capacity && taxiSeat < nearestClients.size(); taxiSeat++) {
      var nextClient = nearestClients.get(taxiSeat).v1();
      nextClient.setMode(MOVING);
      nextTaxi.getPlannedPassengers().add(nextClient);
      nextTaxi
          .getTargets()
          .addOrder(
              Order.of(nextClient.getPosition(), nextClient.getTarget()), TargetList.mergeOrders);
      log.debug("Taxi {} plan client {}", nextTaxi.getName(), nextClient.getName());
    }

    return ok();
  }
}
