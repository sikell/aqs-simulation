package de.sikeller.aqs.taxi.algorithm;

import static de.sikeller.aqs.model.ClientMode.MOVING;

import de.sikeller.aqs.model.*;
import de.sikeller.aqs.model.AlgorithmResult;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TaxiAlgorithmGroupByDirection extends AbstractTaxiAlgorithm implements TaxiAlgorithm {

  @Override
  public SimulationConfiguration getParameters() {
    return new SimulationConfiguration("DetectionRadius", "Color");
  }

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
            Order.of(nextClient.getPosition(), nextClient.getTarget()), TargetList.mergeOrders);
    log.debug("Taxi {} plan client {}", nearestTaxi.getName(), nextClient.getName());
    var direction = nextClient.getPosition().calculateAngle(nextClient.getTarget());

    // search for others with same direction
    var clientsOrderedBySameTarget =
        EntityUtils.sortBy(
            waitingClients, entity -> entity.getPosition().calculateAngle(entity.getTarget()));
    for (Tuple<Client, Double> otherClient : clientsOrderedBySameTarget) {
      // todo this number should be configurable by parameter:
      if (!otherClient.v1().equals(nextClient)
          && isDifferenceSmallerThan(direction, otherClient.v2(), 10)
          && nextClient.getPosition().distance(otherClient.v1().getPosition()) < 100) {
        Client client = otherClient.v1();
        client.setMode(MOVING);
        nearestTaxi.getPlannedPassengers().add(client);
        nearestTaxi
            .getTargets()
            .addOrder(Order.of(client.getPosition(), client.getTarget()), TargetList.mergeOrders);
      }
    }

    return ok();
  }

  /**
   * Checks if the difference between two angles is smaller than maxDegree degrees.
   *
   * @param angle1 The first angle in degrees.
   * @param angle2 The second angle in degrees.
   * @param maxDegree max degree value
   * @return true if the difference between the two angles is smaller than maxDegree degrees, false
   *     otherwise.
   */
  private static boolean isDifferenceSmallerThan(double angle1, double angle2, double maxDegree) {
    angle1 = angle1 % 360;
    angle2 = angle2 % 360;
    double diff = Math.abs(angle1 - angle2);
    if (diff > 180) {
      diff = 360 - diff;
    }
    return diff < maxDegree;
  }
}
