package de.sikeller.aqs.taxi.algorithm;

import de.sikeller.aqs.model.*;

import java.util.Map;
import java.util.Set;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Getter
public class TaxiAlgorithmGroupByDirection extends AbstractTaxiAlgorithm implements TaxiAlgorithm {

  private Map<String, Integer> parameters;
  private final String name = "GroupByDirection";

  public void setParameters(Map<String, Integer> parameters) {
    this.parameters = parameters;
  }

  @Override
  public SimulationConfiguration getParameters() {
    return new SimulationConfiguration(
        new AlgorithmParameter("DetectionRadius"), new AlgorithmParameter("MaxDegree"));
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

    world.mutate().planClientForTaxi(nearestTaxi, nextClient, TargetList.mergeOrders);

    log.debug("Taxi {} plan client {}", nearestTaxi.getName(), nextClient.getName());
    var direction = nextClient.getPosition().calculateAngle(nextClient.getTarget());

    // search for others with same direction
    var clientsOrderedBySameTarget =
        EntityUtils.sortBy(
            waitingClients, entity -> entity.getPosition().calculateAngle(entity.getTarget()));
    for (Tuple<Client, Double> otherClient : clientsOrderedBySameTarget) {
      if (!nearestTaxi.hasCapacity()) {
        break;
      }
      // todo this number should be configurable by parameter:
      if (!otherClient.v1().equals(nextClient)
          && isDifferenceSmallerThan(direction, otherClient.v2(), parameters.get("MaxDegree"))
          && nextClient.getPosition().distance(otherClient.v1().getPosition())
              < parameters.get("DetectionRadius")) {
        Client client = otherClient.v1();
        world.mutate().planClientForTaxi(nearestTaxi, client, TargetList.mergeOrders);
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
