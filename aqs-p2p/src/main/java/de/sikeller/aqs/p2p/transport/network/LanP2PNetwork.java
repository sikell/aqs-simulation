package de.sikeller.aqs.p2p.transport.network;

import de.sikeller.aqs.p2p.api.NodeDescriptor;
import de.sikeller.aqs.p2p.api.NodeRole;
import de.sikeller.aqs.p2p.api.P2PMessage;
import de.sikeller.aqs.p2p.api.P2PNetwork;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;

/**
 * Minimales LAN-P2P-Netzwerk mit UDP-Multicast-Discovery und TCP-Nachrichtenkanal.
 */
@Slf4j
public class LanP2PNetwork implements P2PNetwork {
  private static final String DISCOVERY_TYPE_ANNOUNCE = "ANNOUNCE";
  private static final String DISCOVERY_TYPE_GOODBYE = "GOODBYE";
  private static final long TCP_SERVER_START_TIMEOUT_MILLIS = 2_000;

  private final String multicastGroup;
  private final int discoveryPort;
  private final int tcpPort;
  private final long peerTtlMillis;
  private final long announceIntervalMillis;

  private final Map<String, RemotePeer> peersById = new ConcurrentHashMap<>();
  private final AtomicLong sentMessages = new AtomicLong();
  private final AtomicLong receivedMessages = new AtomicLong();
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final AtomicReference<IOException> tcpServerStartupError = new AtomicReference<>();
  private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(3);

  private volatile NodeDescriptor localNode;
  private volatile Consumer<P2PMessage> messageHandler;
  private volatile MulticastSocket discoverySocket;
  private volatile ServerSocket tcpServer;
  private volatile CountDownLatch tcpServerStartupLatch = new CountDownLatch(0);

  public LanP2PNetwork(int tcpPort) {
    this("239.255.42.99", 45892, tcpPort, 10_000, 2_000);
  }

  public LanP2PNetwork(
      String multicastGroup,
      int discoveryPort,
      int tcpPort,
      long peerTtlMillis,
      long announceIntervalMillis) {
    this.multicastGroup = multicastGroup;
    this.discoveryPort = discoveryPort;
    this.tcpPort = tcpPort;
    this.peerTtlMillis = peerTtlMillis;
    this.announceIntervalMillis = announceIntervalMillis;
  }

  @Override
  public synchronized void join(NodeDescriptor node, Consumer<P2PMessage> handler) {
    if (running.get()) {
      throw new IllegalStateException("Network already running for node " + localNode.id());
    }

    this.localNode = node;
    this.messageHandler = handler;
    startTcpServer();
    awaitTcpServerStartup();
    startDiscovery();
    running.set(true);

    log.info(
        "LAN P2P started for {} on tcp={} discovery={}:{}",
        node.id(),
        tcpPort,
        multicastGroup,
        discoveryPort);
  }

  @Override
  public synchronized void leave(String nodeId) {
    if (!running.get()) {
      return;
    }

    if (localNode != null && localNode.id().equals(nodeId)) {
      sendDiscoveryPacket(DISCOVERY_TYPE_GOODBYE);
    }
    running.set(false);
    peersById.clear();

    if (discoverySocket != null) {
      discoverySocket.close();
      discoverySocket = null;
    }
    if (tcpServer != null) {
      try {
        tcpServer.close();
      } catch (IOException ignored) {
        // socket is closing during shutdown
      }
      tcpServer = null;
    }

    executor.shutdownNow();
    log.info("LAN P2P stopped for {}", nodeId);
  }

  @Override
  public void sendTo(String targetNodeId, P2PMessage message) {
    var peer = peersById.get(targetNodeId);
    if (peer == null) {
      log.debug("Unknown peer '{}', message dropped.", targetNodeId);
      return;
    }

    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(peer.address(), peer.tcpPort()), 1000);
      try (var writer =
          new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
        writer.write(P2PMessageWireCodec.encode(message));
        writer.newLine();
        writer.flush();
      }
      long sent = sentMessages.incrementAndGet();
      log.info(
          "[P2P-NET] send from={} to={} topic={} peers={} sentTotal={}",
          localNode != null ? localNode.id() : "unknown",
          targetNodeId,
          message.topic(),
          peersById.size(),
          sent);
    } catch (IOException e) {
      log.warn("Could not send message to {} at {}:{}", targetNodeId, peer.address(), peer.tcpPort());
    }
  }

  @Override
  public void broadcast(P2PMessage message, Predicate<NodeDescriptor> targetFilter) {
    for (RemotePeer peer : peersById.values()) {
      if (targetFilter.test(peer.node())) {
        sendTo(peer.node().id(), message);
      }
    }
  }

  @Override
  public Set<NodeDescriptor> peers() {
    return peersById.values().stream().map(RemotePeer::node).collect(java.util.stream.Collectors.toSet());
  }

  private void startDiscovery() {
    executor.submit(this::discoveryReceiveLoop);
    executor.scheduleAtFixedRate(
        this::announceAndCleanup,
        0,
        announceIntervalMillis,
        TimeUnit.MILLISECONDS);
  }

  private void startTcpServer() {
    CountDownLatch startupLatch = new CountDownLatch(1);
    tcpServerStartupLatch = startupLatch;
    tcpServerStartupError.set(null);
    executor.submit(
        () -> {
          try (ServerSocket server = new ServerSocket(tcpPort)) {
            this.tcpServer = server;
            startupLatch.countDown();
            while (!Thread.currentThread().isInterrupted()) {
              Socket socket = server.accept();
              executor.submit(() -> handleIncomingConnection(socket));
            }
          } catch (IOException e) {
            if (startupLatch.getCount() > 0) {
              tcpServerStartupError.set(e);
              startupLatch.countDown();
              return;
            }
            if (running.get()) {
              log.error("TCP server failed on port {}", tcpPort, e);
            }
          } finally {
            this.tcpServer = null;
          }
        });
  }

  private void awaitTcpServerStartup() {
    try {
      if (!tcpServerStartupLatch.await(TCP_SERVER_START_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
        throw new IllegalStateException(
            "Timed out while starting TCP server on port " + tcpPort);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Interrupted while starting TCP server on port " + tcpPort, e);
    }

    IOException startupError = tcpServerStartupError.getAndSet(null);
    if (startupError != null) {
      throw new IllegalStateException(
          "Could not start TCP server on port " + tcpPort,
          startupError);
    }
  }

  private void handleIncomingConnection(Socket socket) {
    try (socket;
        var reader =
            new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
      String line = reader.readLine();
      if (line == null || line.isBlank()) {
        return;
      }
      P2PMessage message = P2PMessageWireCodec.decode(line);
      long received = receivedMessages.incrementAndGet();
      log.info(
          "[P2P-NET] recv at={} from={} topic={} peers={} recvTotal={}",
          localNode != null ? localNode.id() : "unknown",
          message.senderId(),
          message.topic(),
          peersById.size(),
          received);
      if (messageHandler != null) {
        messageHandler.accept(message);
      }
    } catch (Exception e) {
      log.debug("Ignoring malformed incoming message: {}", e.getMessage());
    }
  }

  private void discoveryReceiveLoop() {
    try (MulticastSocket socket = new MulticastSocket(discoveryPort)) {
      this.discoverySocket = socket;
      socket.setReuseAddress(true);
      InetAddress group = InetAddress.getByName(multicastGroup);
      socket.joinGroup(new InetSocketAddress(group, discoveryPort), null);

      byte[] buffer = new byte[2048];
      while (!Thread.currentThread().isInterrupted()) {
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        socket.receive(packet);

        String payload =
            new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
        handleDiscoveryPacket(payload, packet.getAddress());
      }
    } catch (SocketException e) {
      // Expected on shutdown.
    } catch (IOException e) {
      log.error("Discovery receiver failed on {}:{}", multicastGroup, discoveryPort, e);
    }
  }

  private void handleDiscoveryPacket(String payload, InetAddress sourceAddress) {
    try {
      String[] parts = payload.split("\\|", -1);
      if (parts.length < 4) {
        return;
      }
      String type = parts[0];
      String nodeId = decodeDiscoveryField(parts[1]);
      NodeRole role = NodeRole.valueOf(parts[2]);
      int peerPort = Integer.parseInt(parts[3]);

      if (localNode != null && localNode.id().equals(nodeId)) {
        return;
      }

      if (DISCOVERY_TYPE_GOODBYE.equals(type)) {
        var removed = peersById.remove(nodeId);
        if (removed != null) {
          log.info("[P2P-DISCOVERY] peer-left id={} peers={}", nodeId, peersById.size());
        }
        return;
      }
      if (!DISCOVERY_TYPE_ANNOUNCE.equals(type)) {
        return;
      }

      NodeDescriptor descriptor = new NodeDescriptor(nodeId, role);
      boolean known = peersById.containsKey(nodeId);
      peersById.put(nodeId, new RemotePeer(descriptor, sourceAddress, peerPort, System.currentTimeMillis()));
      if (!known) {
        log.info(
            "[P2P-DISCOVERY] peer-found id={} role={} host={} tcpPort={} peers={}",
            nodeId,
            role,
            sourceAddress.getHostAddress(),
            peerPort,
            peersById.size());
      }
    } catch (Exception e) {
      log.debug("Ignoring malformed discovery packet: {}", payload);
    }
  }

  private void announceAndCleanup() {
    if (!running.get()) {
      return;
    }
    sendDiscoveryPacket(DISCOVERY_TYPE_ANNOUNCE);

    long now = System.currentTimeMillis();
    int before = peersById.size();
    peersById.entrySet().removeIf(entry -> now - entry.getValue().lastSeenMillis() > peerTtlMillis);
    int removedByTtl = before - peersById.size();
    if (removedByTtl > 0) {
      log.info("[P2P-DISCOVERY] peer-timeout removed={} peers={}", removedByTtl, peersById.size());
    }
  }

  private void sendDiscoveryPacket(String type) {
    NodeDescriptor node = localNode;
    if (node == null) {
      return;
    }

    String payload =
        String.join(
            "|",
            type,
            encodeDiscoveryField(node.id()),
            node.role().name(),
            String.valueOf(tcpPort));
    byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);

    try (MulticastSocket sender = new MulticastSocket()) {
      DatagramPacket packet =
          new DatagramPacket(
              bytes,
              bytes.length,
              InetAddress.getByName(multicastGroup),
              discoveryPort);
      sender.send(packet);
    } catch (IOException e) {
      log.debug("Could not send discovery packet {}", type);
    }
  }

  private String encodeDiscoveryField(String value) {
    return Base64.getUrlEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private String decodeDiscoveryField(String value) {
    return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
  }


  private record RemotePeer(
      NodeDescriptor node, InetAddress address, int tcpPort, long lastSeenMillis) {}
}


