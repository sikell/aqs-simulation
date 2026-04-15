package de.sikeller.aqs.p2p.bootstrap;

import de.sikeller.aqs.p2p.api.P2PSystemProperties;
import de.sikeller.aqs.p2p.service.VehicleP2PService;
import de.sikeller.aqs.p2p.transport.network.LanP2PNetwork;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class VehicleNodeMain {
  private static final int DEFAULT_VEHICLE_TCP_PORT = 46001;
  private static final int DEFAULT_VEHICLE_PORT_BASE = 46000;

  public static void main(String[] args) throws Exception {
    String nodeId = arg(args, "--id", "vehicle-1");
    String explicitTcpPort = arg(args, "--tcpPort", null);
    int tcpPort = resolveTcpPort(nodeId, explicitTcpPort);
    int discoveryPort = Integer.parseInt(arg(args, "--discoveryPort", "45892"));
    String multicastGroup = arg(args, "--multicastGroup", "239.255.42.99");
    String overlayMinNeighbors = arg(args, "--overlayMinNeighbors", null);
    String overlayMaxDistance = arg(args, "--overlayMaxDistance", null);
    String overlayShortcuts = arg(args, "--overlayShortcuts", null);
    String overlayPositionTtlTicks = arg(args, "--overlayPositionTtlTicks", null);

    setIfPresent(P2PSystemProperties.OVERLAY_MIN_NEIGHBORS, overlayMinNeighbors);
    setIfPresent(P2PSystemProperties.OVERLAY_MAX_DISTANCE, overlayMaxDistance);
    setIfPresent(P2PSystemProperties.OVERLAY_SHORTCUTS, overlayShortcuts);
    setIfPresent(P2PSystemProperties.OVERLAY_POSITION_TTL_TICKS, overlayPositionTtlTicks);

    if (explicitTcpPort == null || explicitTcpPort.isBlank()) {
      log.info("No --tcpPort provided for '{}'. Using derived default tcpPort={}", nodeId, tcpPort);
    }

    var network = new LanP2PNetwork(multicastGroup, discoveryPort, tcpPort, 10_000, 2_000);
    var stopLatch = new CountDownLatch(1);

    try (var vehicle = new VehicleP2PService(nodeId, network);
        var scheduler = Executors.newSingleThreadScheduledExecutor()) {
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    vehicle.stop();
                    scheduler.shutdownNow();
                    stopLatch.countDown();
                  }));

      vehicle.start();
      log.info("Vehicle node '{}' started. Press Ctrl+C to stop.", nodeId);
      scheduler.scheduleAtFixedRate(
          () -> log.info("[P2P-NODE] {}", vehicle.runtimeStatus().asLogLine()),
          2,
          5,
          TimeUnit.SECONDS);
      stopLatch.await();
    }
  }

  private static String arg(String[] args, String key, String defaultValue) {
    for (String arg : args) {
      if (arg.startsWith(key + "=")) {
        return arg.substring((key + "=").length());
      }
    }
    return defaultValue;
  }

  static int resolveTcpPort(String nodeId, String explicitTcpPort) {
    if (explicitTcpPort != null && !explicitTcpPort.isBlank()) {
      return parsePort(explicitTcpPort);
    }

    String normalized = nodeId == null ? "" : nodeId.trim().toLowerCase(Locale.ROOT);
    if (normalized.startsWith("vehicle-")) {
      String suffix = normalized.substring("vehicle-".length());
      if (suffix.matches("\\d+")) {
        int vehicleIndex = Integer.parseInt(suffix);
        int derived = DEFAULT_VEHICLE_PORT_BASE + vehicleIndex;
        if (derived >= 1 && derived <= 65535) {
          return derived;
        }
      }
    }

    return DEFAULT_VEHICLE_TCP_PORT;
  }

  private static int parsePort(String value) {
    try {
      int port = Integer.parseInt(value);
      if (port < 1 || port > 65535) {
        throw new IllegalArgumentException("--tcpPort must be in range 1..65535");
      }
      return port;
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("--tcpPort must be numeric", e);
    }
  }

  private static void setIfPresent(String key, String value) {
    if (value == null || value.isBlank()) {
      return;
    }
    System.setProperty(key, value.trim());
  }
}


