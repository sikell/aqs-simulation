package de.sikeller.aqs.p2p.transport.network;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.sikeller.aqs.p2p.api.NodeDescriptor;
import de.sikeller.aqs.p2p.api.NodeRole;
import java.io.IOException;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;

class LanP2PNetworkTest {

  @Test
  void joinFailsFastWhenTcpPortAlreadyInUse() throws Exception {
    int discoveryPort = findFreePort();
    int sharedTcpPort = findFreePort();

    LanP2PNetwork first =
        new LanP2PNetwork("239.255.42.99", discoveryPort, sharedTcpPort, 10_000, 2_000);
    LanP2PNetwork second =
        new LanP2PNetwork("239.255.42.99", discoveryPort, sharedTcpPort, 10_000, 2_000);

    first.join(new NodeDescriptor("vehicle-1", NodeRole.VEHICLE), message -> {});

    try {
      IllegalStateException exception =
          assertThrows(
              IllegalStateException.class,
              () -> second.join(new NodeDescriptor("vehicle-2", NodeRole.VEHICLE), message -> {}));
      assertTrue(exception.getMessage().contains("Could not start TCP server on port"));
    } finally {
      second.leave("vehicle-2");
      first.leave("vehicle-1");
    }
  }

  private static int findFreePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}

