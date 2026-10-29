package de.sikeller.aqs.p2p.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.sikeller.aqs.model.Position;
import de.sikeller.aqs.model.SpawnScenario;
import de.sikeller.aqs.p2p.api.P2PPayloadKeys;
import de.sikeller.aqs.p2p.api.P2PSystemProperties;
import de.sikeller.aqs.p2p.transport.inmemory.InMemoryP2PNetwork;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VehicleP2PServiceIdleTest {

  @BeforeEach
  void before() {
    System.setProperty(P2PSystemProperties.VEHICLE_ROAMING_ENABLED, "true");
    System.setProperty(P2PSystemProperties.VEHICLE_IDLE_THRESHOLD_TICKS, "1");
    System.setProperty(P2PSystemProperties.VEHICLE_IDLE_CHECK_THROTTLE_TICKS, "1");
  }

  @AfterEach
  void after() {
    System.clearProperty(P2PSystemProperties.VEHICLE_ROAMING_ENABLED);
    System.clearProperty(P2PSystemProperties.VEHICLE_IDLE_THRESHOLD_TICKS);
    System.clearProperty(P2PSystemProperties.VEHICLE_IDLE_CHECK_THROTTLE_TICKS);
    System.clearProperty(P2PSystemProperties.VEHICLE_IDLE_ROAMING_STRATEGY);
    System.clearProperty(P2PSystemProperties.VEHICLE_REQUEST_CACHE_TTL_TICKS);
    System.clearProperty(P2PSystemProperties.VEHICLE_IDLE_SEEN_CLIENT_TTL_TICKS);
  }

  @Test
  void pollIdleTravelTarget_producesOneTimeTarget() {
    var network = new InMemoryP2PNetwork();
    VehicleP2PService vehicle = new VehicleP2PService("vehicle-1", network);
    try {
      vehicle.start();
      vehicle.setMapBounds(1000, 1000);

      // initialize position and tick 0
      vehicle.setSimulationState(true, 100, 100, 0L);

      Optional<Position> found = Optional.empty();
      // advance a few ticks until idle target produced
      for (long tick = 1; tick <= 10; tick++) {
        vehicle.checkIdleTravelAtStep(tick);
        found = vehicle.pollIdleTravelTarget();
        if (found.isPresent()) {
          break;
        }
      }

      assertTrue(found.isPresent(), "Expected an idle travel target to be published");
      Position p = found.get();
      assertNotNull(p);
      // subsequent poll must be empty (one-time consumption)
      Optional<Position> again = vehicle.pollIdleTravelTarget();
      assertFalse(again.isPresent());
    } finally {
      vehicle.stop();
    }
  }

  @Test
  void pollIdleTravelTarget_returnToHqSpatialImbalanceTargetsCenter() {
    System.setProperty(P2PSystemProperties.VEHICLE_IDLE_ROAMING_STRATEGY, "return-to-hq");

    var network = new InMemoryP2PNetwork();
    VehicleP2PService vehicle = new VehicleP2PService("vehicle-1", network);
    try {
      vehicle.start();
      vehicle.setMapBounds(1200, 800);
      vehicle.setSpawnScenario(SpawnScenario.SPATIAL_IMBALANCE);
      vehicle.setSimulationState(true, 100, 100, 0L);

      Optional<Position> found = Optional.empty();
      for (long tick = 1; tick <= 10; tick++) {
        vehicle.checkIdleTravelAtStep(tick);
        found = vehicle.pollIdleTravelTarget();
        if (found.isPresent()) {
          break;
        }
      }

      assertTrue(found.isPresent(), "Expected an idle travel target to be published");
      Position p = found.get();
      assertNotNull(p);
      assertEquals(600, p.getX());
      assertEquals(400, p.getY());
    } finally {
      vehicle.stop();
    }
  }

  @Test
  void pastAvgTotal_waitsForDataThenPublishesSeenClientTarget() {
    System.setProperty(P2PSystemProperties.VEHICLE_IDLE_ROAMING_STRATEGY, "past-avg-total");

    var network = new InMemoryP2PNetwork();
    VehicleP2PService vehicle = new VehicleP2PService("vehicle-1", network);
    try {
      vehicle.start();
      vehicle.setMapBounds(1000, 1000);
      vehicle.setSimulationState(true, 100, 100, 0L);

      vehicle.checkIdleTravelAtStep(1L);
      assertFalse(
          vehicle.pollIdleTravelTarget().isPresent(),
          "Missing data must not create a persistent no-op target");

      vehicle.getIdleRoamingController().registerSeenClientPosition(700, 800, 2L);
      vehicle.checkIdleTravelAtStep(2L);

      Position target = vehicle.pollIdleTravelTarget().orElseThrow();
      assertEquals(700, target.getX());
      assertEquals(800, target.getY());
    } finally {
      vehicle.stop();
    }
  }

  @Test
  void seenRequestIsNotRegisteredAgainBeforeItsRoamingTtlExpires() {
    System.setProperty(P2PSystemProperties.VEHICLE_REQUEST_CACHE_TTL_TICKS, "120");
    System.setProperty(P2PSystemProperties.VEHICLE_IDLE_SEEN_CLIENT_TTL_TICKS, "1000");

    var network = new InMemoryP2PNetwork();
    VehicleP2PService vehicle = new VehicleP2PService("vehicle-1", network);
    try {
      vehicle.start();
      vehicle.setSimulationState(false, 100, 100, 0L);
      vehicle.deliverRideRequestDirect(
          "request-1",
          "client-1",
          Map.of(P2PPayloadKeys.REQUEST_X, "100", P2PPayloadKeys.REQUEST_Y, "100"));

      vehicle.advanceSimulationTick(121L);
      vehicle.deliverRideRequestDirect(
          "request-1",
          "client-1",
          Map.of(P2PPayloadKeys.REQUEST_X, "900", P2PPayloadKeys.REQUEST_Y, "900"));

      int[] average = vehicle.getIdleRoamingController().getAvgTotalPositionSnapshot();
      assertNotNull(average);
      assertEquals(100, average[0]);
      assertEquals(100, average[1]);

      vehicle.advanceSimulationTick(1000L);
      vehicle.deliverRideRequestDirect(
          "request-1",
          "client-1",
          Map.of(P2PPayloadKeys.REQUEST_X, "900", P2PPayloadKeys.REQUEST_Y, "900"));

      average = vehicle.getIdleRoamingController().getAvgTotalPositionSnapshot();
      assertNotNull(average);
      assertEquals(900, average[0]);
      assertEquals(900, average[1]);
    } finally {
      vehicle.stop();
    }
  }
}
