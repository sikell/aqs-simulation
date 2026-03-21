package de.sikeller.aqs.p2p.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class VehicleNodeMainTest {

  @Test
  void explicitTcpPortOverridesDerivedDefault() {
    int resolved = VehicleNodeMain.resolveTcpPort("vehicle-2", "47077");

    assertEquals(47077, resolved);
  }

  @Test
  void derivedTcpPortUsesVehicleSuffix() {
    int resolved = VehicleNodeMain.resolveTcpPort("vehicle-2", null);

    assertEquals(46002, resolved);
  }

  @Test
  void missingNumericSuffixFallsBackToLegacyDefault() {
    int resolved = VehicleNodeMain.resolveTcpPort("vehicle-x", null);

    assertEquals(46001, resolved);
  }

  @Test
  void invalidExplicitPortThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> VehicleNodeMain.resolveTcpPort("vehicle-1", "99999"));
  }
}

