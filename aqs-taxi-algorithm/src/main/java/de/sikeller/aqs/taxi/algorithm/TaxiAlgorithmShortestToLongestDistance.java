package de.sikeller.aqs.taxi.algorithm;

import de.sikeller.aqs.model.*;

import static de.sikeller.aqs.model.ClientMode.MOVING;
import static org.reflections.Reflections.log;

public class TaxiAlgorithmShortestToLongestDistance extends AbstractTaxiAlgorithm implements TaxiAlgorithm {
    @Override
    public SimulationConfiguration getParameters() {
        return new SimulationConfiguration("test1", "test2");
    }

    @Override
    public AlgorithmResult nextStep(World world) {
        var waitingClients = getWaitingClients(world);
        if (waitingClients.isEmpty()) {
            return stop("No clients waiting for a taxi");
        }

        var nextClient = waitingClients.iterator().next();
        for (Client client : waitingClients) {
            double distanceNextClient = nextClient.getPosition().distance(nextClient.getTarget());
            double distanceNewClient = client.getPosition().distance(client.getTarget());
            if (distanceNewClient < distanceNextClient) nextClient = client;
        }
//        var nextClient = distanceTracker.;
        var taxiCanidates = getEmptyTaxis(world);
        if (taxiCanidates.isEmpty()) {
            return stop("No taxis with capacity found");
        }

        var nearestTaxiWithCapacity = EntityUtils.findNearestNeighbor(nextClient.getPosition(), taxiCanidates);
        if (nearestTaxiWithCapacity == null) {
            return fail("No nearest taxi found");
        }

        nextClient.setMode(MOVING);
        Taxi nearestTaxi = nearestTaxiWithCapacity.v1();
        nearestTaxi.getPlannedPassengers().add(nextClient);

        nearestTaxi
                .getTargets()
                .addOrder(
                        Order.of(nextClient.getPosition(), nextClient.getTarget()), TargetList.sequentialOrders);
        log.debug("Taxi {} plan client {}", nearestTaxi.getName(), nextClient.getName());


        return null;
    }
}
