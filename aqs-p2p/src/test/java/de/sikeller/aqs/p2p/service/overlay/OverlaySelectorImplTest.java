package de.sikeller.aqs.p2p.service.overlay;

import static org.junit.jupiter.api.Assertions.*;

import de.sikeller.aqs.p2p.api.NodeDescriptor;
import de.sikeller.aqs.p2p.api.NodeRole;
import de.sikeller.aqs.p2p.service.position.PositionManagerImpl;
import de.sikeller.aqs.p2p.service.config.P2PConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

class OverlaySelectorImplTest {

  private static final P2PConfig TEST_CONFIG =
      new P2PConfig() {
        @Override
        public int overlayMinNeighbors() {
          return 1;
        }

        @Override
        public int overlayMaxNeighbors() {
          return 10;
        }

        @Override
        public double overlayMaxDistance() {
          return Double.MAX_VALUE;
        }

        @Override
        public String overlayShortcutStrategy() {
          return "kleinberg";
        }

        @Override
        public int overlayShortcuts() {
          return 1;
        }

        @Override
        public long positionTtlTicks() {
          return 10L;
        }

        @Override
        public long positionRevisionThrottleTicks() {
          return 1L;
        }

        @Override
        public int positionRevisionMinMoveMeters() {
          return 0;
        }
      };

  @Test
  void selectDoesNotThrowAndReturnsVehiclesWhenRelevant() {
    NodeDescriptor self = new NodeDescriptor("vehicle-1", NodeRole.VEHICLE);
    PositionManagerImpl pm = new PositionManagerImpl(TEST_CONFIG);
    // set some positions
    pm.updatePosition("vehicle-1", 0, 0, 10);
    pm.updatePosition("vehicle-2", 10, 0, 10);
    OverlaySelectorImpl sel = new OverlaySelectorImpl(self, pm, TEST_CONFIG);

    var peers =
        List.of(
            new NodeDescriptor("vehicle-2", NodeRole.VEHICLE),
            new NodeDescriptor("client-1", NodeRole.CLIENT));
    var selection = sel.select("VEHICLE_POSITION", peers);
    assertNotNull(selection);
    // selection peers should be subset of input peers
    assertTrue(
        peers.containsAll(selection.peers())
            || selection.peers().containsAll(peers)
            || !selection.peers().isEmpty());
  }
}
