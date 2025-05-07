package de.sikeller.aqs.model;

import static org.junit.jupiter.api.Assertions.*;
class PositionTest {

  @org.junit.jupiter.api.Test
  void moveTowardsLargeNumbers() {
    // move towards should properly handle large integer input values
    var position = new Position(35424, 3651);
    var target = new Position(2642, 39062);
    var result = position.moveTowards(target, 540.277777777777);
    assertEquals(35057, result.getX());
    assertEquals(4047, result.getY());
  }
}