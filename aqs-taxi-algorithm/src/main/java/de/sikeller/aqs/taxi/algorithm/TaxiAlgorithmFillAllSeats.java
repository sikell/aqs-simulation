package de.sikeller.aqs.taxi.algorithm;

import static de.sikeller.aqs.model.ClientMode.MOVING;

import de.sikeller.aqs.model.*;
import de.sikeller.aqs.taxi.algorithm.model.AlgorithmResult;
import java.util.LinkedList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TaxiAlgorithmFillAllSeats extends AbstractTaxiAlgorithm implements TaxiAlgorithm {
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
              Order.of(nextClient.getPosition(), nextClient.getTarget()),
              orders -> {
                List<Position> result = new LinkedList<>();
                int maxSize = orders.stream().mapToInt(Order::size).max().orElse(0);
                for (int i = 0; i < maxSize; i++) {
                  for (Order innerList : orders) {
                    if (i < innerList.size()) {
                      result.add(innerList.get(i));
                    }
                  }
                }
                return result;
              });
      log.debug("Taxi {} plan client {}", nextTaxi.getName(), nextClient.getName());
    }

    return ok();
  }
}
