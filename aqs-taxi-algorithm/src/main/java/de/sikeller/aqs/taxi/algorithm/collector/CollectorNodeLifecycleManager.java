package de.sikeller.aqs.taxi.algorithm.collector;

import de.sikeller.aqs.p2p.api.P2PNetwork;
import de.sikeller.aqs.p2p.api.P2PSystemProperties;
import de.sikeller.aqs.p2p.service.ClientP2PService;
import de.sikeller.aqs.p2p.transport.inmemory.InMemoryP2PNetwork;
import de.sikeller.aqs.p2p.transport.network.LanP2PNetwork;
import de.sikeller.aqs.p2p.util.P2PRunContext;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/** Owns collector node bootstrap/shutdown for embedded and LAN modes. */
@Slf4j
public class CollectorNodeLifecycleManager {
  private static final String P2P_TCP_PORT = "p2pTcpPort";
  private static final String P2P_DISCOVERY_PORT = "p2pDiscoveryPort";
  private static final String EMBEDDED_MODE_PROPERTY = "p2pEmbeddedSimulation";

  @FunctionalInterface
  interface EmbeddedNetworkFactory {
    P2PNetwork create();
  }

  @FunctionalInterface
  interface LanNetworkFactory {
    P2PNetwork create(
        String multicastGroup,
        int discoveryPort,
        int tcpPort,
        int connectTimeoutMs,
        int discoveryTimeoutMs);
  }

  @FunctionalInterface
  interface ClientNodeFactory {
    ClientP2PService create(String nodeId, P2PNetwork network);
  }

  private final String localCollectorNodeId;
  private final String collectorNodePrefix;
  private final EmbeddedNetworkFactory embeddedNetworkFactory;
  private final LanNetworkFactory lanNetworkFactory;
  private final ClientNodeFactory clientNodeFactory;

  private P2PNetwork network;
  private ClientP2PService clientNode;
  private String collectorNodeId = "";

  public CollectorNodeLifecycleManager(String localCollectorNodeId, String collectorNodePrefix) {
    this(
        localCollectorNodeId,
        collectorNodePrefix,
        InMemoryP2PNetwork::new,
            LanP2PNetwork::new,
        ClientP2PService::new);
  }

  CollectorNodeLifecycleManager(
      String localCollectorNodeId,
      String collectorNodePrefix,
      EmbeddedNetworkFactory embeddedNetworkFactory,
      LanNetworkFactory lanNetworkFactory,
      ClientNodeFactory clientNodeFactory) {
    this.localCollectorNodeId = localCollectorNodeId;
    this.collectorNodePrefix = collectorNodePrefix;
    this.embeddedNetworkFactory = embeddedNetworkFactory;
    this.lanNetworkFactory = lanNetworkFactory;
    this.clientNodeFactory = clientNodeFactory;
  }

  public void ensureCollectorNode(Map<String, Integer> config, String multicastGroup) {
    if (clientNode != null && network != null) {
      if (!collectorNodeId.isBlank()) {
        P2PRunContext.setProperty(P2PSystemProperties.OVERLAY_COLLECTOR_NODE_ID, collectorNodeId);
      }
      return;
    }

    if (isEmbeddedSimulationMode(config)) {
      collectorNodeId = localCollectorNodeId;
      P2PRunContext.setProperty(P2PSystemProperties.OVERLAY_COLLECTOR_NODE_ID, collectorNodeId);
      network = embeddedNetworkFactory.create();
      clientNode = clientNodeFactory.create(collectorNodeId, network);
      clientNode.start();
      log.info("P2P collector node started in LOCAL simulation mode: id={}", collectorNodeId);
      return;
    }

    int tcpPort = config.getOrDefault(P2P_TCP_PORT, 46100);
    int discoveryPort = config.getOrDefault(P2P_DISCOVERY_PORT, 45892);
    collectorNodeId = collectorNodePrefix + tcpPort;
    P2PRunContext.setProperty(P2PSystemProperties.OVERLAY_COLLECTOR_NODE_ID, collectorNodeId);

    network = lanNetworkFactory.create(multicastGroup, discoveryPort, tcpPort, 10_000, 2_000);
    clientNode = clientNodeFactory.create(collectorNodeId, network);
    clientNode.start();

    log.info(
        "P2P collector node started: id={}, tcpPort={}, discoveryPort={}, multicastGroup={}",
        collectorNodeId,
        tcpPort,
        discoveryPort,
        multicastGroup);
  }

  public void stopCollectorNode() {
    if (clientNode != null) {
      clientNode.stop();
      clientNode = null;
    }
    network = null;
    if (!collectorNodeId.isBlank()) {
      String configuredCollector =
          P2PRunContext.getProperty(P2PSystemProperties.OVERLAY_COLLECTOR_NODE_ID, "");
      if (collectorNodeId.equals(configuredCollector)) {
        P2PRunContext.setProperty(P2PSystemProperties.OVERLAY_COLLECTOR_NODE_ID, null);
      }
      collectorNodeId = "";
    }
  }

  public P2PNetwork network() {
    return network;
  }

  public ClientP2PService clientNode() {
    return clientNode;
  }

  public String collectorNodeId() {
    return collectorNodeId;
  }

  private boolean isEmbeddedSimulationMode(Map<String, Integer> config) {
    return config.getOrDefault(EMBEDDED_MODE_PROPERTY, 1) == 1;
  }
}

