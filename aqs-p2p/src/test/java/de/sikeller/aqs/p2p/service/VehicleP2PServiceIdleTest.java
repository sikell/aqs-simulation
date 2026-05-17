package de.sikeller.aqs.p2p.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import de.sikeller.aqs.model.Position;
import de.sikeller.aqs.model.SpawnScenario;
import de.sikeller.aqs.p2p.api.P2PSystemProperties;
import de.sikeller.aqs.p2p.transport.inmemory.InMemoryP2PNetwork;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VehicleP2PServiceIdleTest {

  @BeforeEach
  void before() {
    System.setProperty(P2PSystemProperties.VEHICLE_IDLE_RANDOM_TRAVEL_ENABLED, "true");
    System.setProperty(P2PSystemProperties.VEHICLE_IDLE_THRESHOLD_TICKS, "1");
    System.setProperty(P2PSystemProperties.VEHICLE_IDLE_CHECK_THROTTLE_TICKS, "1");
  }

  @AfterEach
  void after() {
    System.clearProperty(P2PSystemProperties.VEHICLE_IDLE_RANDOM_TRAVEL_ENABLED);
    System.clearProperty(P2PSystemProperties.VEHICLE_IDLE_THRESHOLD_TICKS);
    System.clearProperty(P2PSystemProperties.VEHICLE_IDLE_CHECK_THROTTLE_TICKS);
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
  void pollIdleTravelTarget_pageRankSpatialImbalanceTargetsCenter() {
    System.setProperty(P2PSystemProperties.VEHICLE_IDLE_ROAMING_STRATEGY, "page-rank");

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
      System.clearProperty(P2PSystemProperties.VEHICLE_IDLE_ROAMING_STRATEGY);
    }
  }
}

