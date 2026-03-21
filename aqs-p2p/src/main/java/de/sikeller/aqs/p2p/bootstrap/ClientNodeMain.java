package de.sikeller.aqs.p2p.bootstrap;

import de.sikeller.aqs.p2p.service.ClientP2PService;
import de.sikeller.aqs.p2p.transport.network.LanP2PNetwork;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ClientNodeMain {

  public static void main(String[] args) throws Exception {
    String nodeId = arg(args, "--id", "client-1");
    int requestHops = Integer.parseInt(arg(args, "--requestHops", "2"));
    int tcpPort = Integer.parseInt(arg(args, "--tcpPort", "46002"));
    int discoveryPort = Integer.parseInt(arg(args, "--discoveryPort", "45892"));
    String multicastGroup = arg(args, "--multicastGroup", "239.255.42.99");

    var network = new LanP2PNetwork(multicastGroup, discoveryPort, tcpPort, 10_000, 2_000);
    var stopLatch = new CountDownLatch(1);

    try (var client = new ClientP2PService(nodeId, network);
        var scheduler = Executors.newSingleThreadScheduledExecutor()) {

      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    client.stop();
                    scheduler.shutdownNow();
                    stopLatch.countDown();
                  }));

      client.start();
      log.info("Client node '{}' started. Sending requests every 10s. Press Ctrl+C to stop.", nodeId);
      scheduler.scheduleAtFixedRate(
          () ->
              client.requestRide(
                  "(100,100)",
                  "(900,900)",
                  node -> !node.id().equals(client.descriptor().id()),
                  requestHops,
                  ""),
          1,
          10,
          TimeUnit.SECONDS);
      scheduler.scheduleAtFixedRate(
          () -> log.info("[P2P-NODE] {}", client.runtimeStatus().asLogLine()),
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
}



