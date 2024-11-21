package de.sikeller.aqs.taxi.algorithm;

import de.sikeller.aqs.model.EntityUtils;
import de.sikeller.aqs.model.TargetList;
import de.sikeller.aqs.model.Taxi;
import de.sikeller.aqs.model.World;
import de.sikeller.aqs.taxi.algorithm.model.AlgorithmResult;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

import static de.sikeller.aqs.model.ClientMode.MOVING;

@Slf4j
public class TaxiAlgorithmSimple extends AbstractTaxiAlgorithm implements TaxiAlgorithm {
  @Override
  public AlgorithmResult nextStep(World world) {
    var waitingClients = getWaitingClients(world);
    if (waitingClients.isEmpty()) return stop("No clients waiting for a taxi.");
    var nextClient = waitingClients.iterator().next();

    var taxiCandidates = getTaxisWithCapacity(world);
    if (taxiCandidates.isEmpty()) return stop("No taxis with capacity found.");

    var nearestTaxiWithCapacity = EntityUtils.findNearestNeighbor(nextClient, taxiCandidates);
    if (nearestTaxiWithCapacity == null) return fail("No nearest taxi found.");

    nextClient.setMode(MOVING);
    Taxi nearestTaxi = nearestTaxiWithCapacity.v1();
    nearestTaxi.getPlannedPassengers().add(nextClient);

    nearestTaxi
        .getTargets()
        .addOrder(
            List.of(nextClient.getPosition(), nextClient.getTarget()),
            TargetList.defaultFlattenOrder);
    log.debug("Taxi {} plan client {}", nearestTaxi.getName(), nextClient.getName());

    return ok();
  }
}
