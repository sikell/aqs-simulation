package de.sikeller.aqs.taxi.algorithm;

import static de.sikeller.aqs.model.ClientMode.MOVING;

import de.sikeller.aqs.model.*;
import de.sikeller.aqs.taxi.algorithm.model.AlgorithmResult;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;


@Slf4j
public class TaxiAlgorithmSinglePassenger extends AbstractTaxiAlgorithm implements TaxiAlgorithm {

    @Override
  public AlgorithmResult nextStep(World world) {
    var waitingClients = getWaitingClients(world);
    if (waitingClients.isEmpty()) return stop("No clients waiting for a taxi.");
    var nextClient = waitingClients.iterator().next();

    var taxiCandidates = getEmptyTaxis(world);
    if (taxiCandidates.isEmpty()) return stop("No taxis with capacity found.");

    var nearestTaxiWithCapacity =
        EntityUtils.findNearestNeighbor(nextClient.getPosition(), taxiCandidates);
    if (nearestTaxiWithCapacity == null) return fail("No nearest taxi found.");

    nextClient.setMode(MOVING);
    Taxi nearestTaxi = nearestTaxiWithCapacity.v1();
    nearestTaxi.getPlannedPassengers().add(nextClient);

    nearestTaxi
        .getTargets()
        .addOrder(
            Order.of(nextClient.getPosition(), nextClient.getTarget()),
            TargetList.defaultFlattenOrder);
    log.debug("Taxi {} plan client {}", nearestTaxi.getName(), nextClient.getName());

    return ok();
  }


}
