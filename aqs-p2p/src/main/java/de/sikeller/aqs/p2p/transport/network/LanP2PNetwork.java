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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;

/** Minimales LAN-P2P-Netzwerk mit UDP-Multicast-Discovery und TCP-Nachrichtenkanal. */
@Slf4j
public class LanP2PNetwork implements P2PNetwork {
  private static final String DISCOVERY_TYPE_ANNOUNCE = "ANNOUNCE";
  private static final String DISCOVERY_TYPE_GOODBYE = "GOODBYE";
  private static final long TCP_SERVER_START_TIMEOUT_MILLIS = 2_000;
  private static final String IO_MAX_THREADS_PROPERTY = "aqs.p2p.lan.io.maxThreads";
  private static final int DEFAULT_IO_MAX_THREADS =
      Math.max(16, Runtime.getRuntime().availableProcessors() * 4);
  private static final AtomicInteger IO_THREAD_COUNTER = new AtomicInteger();

  private final String multicastGroup;
  private final int discoveryPort;
  private final int tcpPort;
  private final long peerTtlMillis;
  private final long announceIntervalMillis;

  private final Map<String, RemotePeer> peersById = new ConcurrentHashMap<>();
  private volatile Set<NodeDescriptor> peersSnapshot = Set.of();
  private volatile boolean peersSnapshotDirty = true;
  private final AtomicLong sentMessages = new AtomicLong();
  private final AtomicLong receivedMessages = new AtomicLong();
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final AtomicReference<IOException> tcpServerStartupError = new AtomicReference<>();
  private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
  private final ExecutorService ioExecutor =
      new ThreadPoolExecutor(
          2,
          Integer.getInteger(IO_MAX_THREADS_PROPERTY, DEFAULT_IO_MAX_THREADS),
          60L,
          TimeUnit.SECONDS,
          new SynchronousQueue<>(),
          r -> {
            Thread t = new Thread(r, "p2p-io-" + IO_THREAD_COUNTER.incrementAndGet());
            t.setDaemon(true);
            return t;
          },
          new ThreadPoolExecutor.CallerRunsPolicy());
  private final Map<String, PooledConnection> outboundConnections = new ConcurrentHashMap<>();

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
    peersSnapshot = Set.of();
    peersSnapshotDirty = false;
    closeAllOutboundConnections();

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

    scheduler.shutdownNow();
    ioExecutor.shutdownNow();
    log.info("LAN P2P stopped for {}", nodeId);
  }

  @Override
  public void sendTo(String targetNodeId, P2PMessage message) {
    var peer = peersById.get(targetNodeId);
    if (peer == null) {
      log.debug("Unknown peer '{}', message dropped.", targetNodeId);
      return;
    }

    String encoded = P2PMessageWireCodec.encode(message);
    if (!sendViaPooledConnection(peer, encoded)) {
      log.warn(
          "Could not send message to {} at {}:{}",
          targetNodeId,
          peer.address(),
          peer.tcpPort());
      return;
    }

    long sent = sentMessages.incrementAndGet();
    log.debug(
        "[P2P-NET] send from={} to={} topic={} peers={} sentTotal={}",
        localNode != null ? localNode.id() : "unknown",
        targetNodeId,
        message.topic(),
        peersById.size(),
        sent);
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
    if (!peersSnapshotDirty) {
      return peersSnapshot;
    }
    synchronized (this) {
      if (peersSnapshotDirty) {
        peersSnapshot =
            peersById.values().stream()
                .map(RemotePeer::node)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        peersSnapshotDirty = false;
      }
      return peersSnapshot;
    }
  }

  private void startDiscovery() {
    ioExecutor.submit(this::discoveryReceiveLoop);
    scheduler.scheduleAtFixedRate(
        this::announceAndCleanup, 0, announceIntervalMillis, TimeUnit.MILLISECONDS);
  }

  private void startTcpServer() {
    CountDownLatch startupLatch = new CountDownLatch(1);
    tcpServerStartupLatch = startupLatch;
    tcpServerStartupError.set(null);
    ioExecutor.submit(
        () -> {
          try (ServerSocket server = new ServerSocket(tcpPort)) {
            this.tcpServer = server;
            startupLatch.countDown();
            while (!Thread.currentThread().isInterrupted()) {
              Socket socket = server.accept();
              ioExecutor.submit(() -> handleIncomingConnection(socket));
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
        throw new IllegalStateException("Timed out while starting TCP server on port " + tcpPort);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Interrupted while starting TCP server on port " + tcpPort, e);
    }

    IOException startupError = tcpServerStartupError.getAndSet(null);
    if (startupError != null) {
      throw new IllegalStateException(
          "Could not start TCP server on port " + tcpPort, startupError);
    }
  }

  private void handleIncomingConnection(Socket socket) {
    try (socket;
        var reader =
            new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) {
          continue;
        }
        P2PMessage message = P2PMessageWireCodec.decode(line);
        long received = receivedMessages.incrementAndGet();
        log.debug(
            "[P2P-NET] recv at={} from={} topic={} peers={} recvTotal={}",
            localNode != null ? localNode.id() : "unknown",
            message.senderId(),
            message.topic(),
            peersById.size(),
            received);
        if (messageHandler != null) {
          messageHandler.accept(message);
        }
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
            new String(
                packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
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
          peersSnapshotDirty = true;
          closeOutboundConnection(nodeId);
          log.info("[P2P-DISCOVERY] peer-left id={} peers={}", nodeId, peersById.size());
        }
        return;
      }
      if (!DISCOVERY_TYPE_ANNOUNCE.equals(type)) {
        return;
      }

      NodeDescriptor descriptor = new NodeDescriptor(nodeId, role);
      boolean known = peersById.containsKey(nodeId);
      RemotePeer previous =
          peersById.put(
          nodeId, new RemotePeer(descriptor, sourceAddress, peerPort, System.currentTimeMillis()));
      peersSnapshotDirty = true;
      if (previous != null
          && (!previous.address().equals(sourceAddress) || previous.tcpPort() != peerPort)) {
        closeOutboundConnection(nodeId);
      }
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
      peersSnapshotDirty = true;
      outboundConnections.keySet().stream()
          .filter(peerId -> !peersById.containsKey(peerId))
          .toList()
          .forEach(this::closeOutboundConnection);
      log.info("[P2P-DISCOVERY] peer-timeout removed={} peers={}", removedByTtl, peersById.size());
    }
  }

  private boolean sendViaPooledConnection(RemotePeer peer, String encoded) {
    String peerId = peer.node().id();
    for (int attempt = 0; attempt < 2; attempt++) {
      PooledConnection connection =
          outboundConnections.compute(
              peerId,
              (id, existing) -> {
                if (existing != null
                    && existing.matches(peer.address(), peer.tcpPort())
                    && existing.isOpen()) {
                  return existing;
                }
                if (existing != null) {
                  existing.closeQuietly();
                }
                try {
                  return PooledConnection.connect(peer.address(), peer.tcpPort());
                } catch (IOException e) {
                  return null;
                }
              });
      if (connection == null) {
        return false;
      }
      try {
        connection.sendLine(encoded);
        return true;
      } catch (IOException sendError) {
        outboundConnections.remove(peerId, connection);
        connection.closeQuietly();
      }
    }
    return false;
  }

  private void closeOutboundConnection(String peerId) {
    PooledConnection connection = outboundConnections.remove(peerId);
    if (connection != null) {
      connection.closeQuietly();
    }
  }

  private void closeAllOutboundConnections() {
    outboundConnections.values().forEach(PooledConnection::closeQuietly);
    outboundConnections.clear();
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
              bytes, bytes.length, InetAddress.getByName(multicastGroup), discoveryPort);
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

  private static final class PooledConnection {
    private final InetAddress address;
    private final int port;
    private final Socket socket;
    private final BufferedWriter writer;

    private PooledConnection(InetAddress address, int port, Socket socket, BufferedWriter writer) {
      this.address = address;
      this.port = port;
      this.socket = socket;
      this.writer = writer;
    }

    static PooledConnection connect(InetAddress address, int port) throws IOException {
      Socket socket = new Socket();
      socket.connect(new InetSocketAddress(address, port), 1000);
      BufferedWriter writer =
          new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
      return new PooledConnection(address, port, socket, writer);
    }

    synchronized void sendLine(String encoded) throws IOException {
      writer.write(encoded);
      writer.newLine();
      writer.flush();
    }

    boolean matches(InetAddress expectedAddress, int expectedPort) {
      return port == expectedPort && address.equals(expectedAddress);
    }

    boolean isOpen() {
      return socket.isConnected() && !socket.isClosed();
    }

    void closeQuietly() {
      try {
        writer.close();
      } catch (IOException ignored) {
      }
      try {
        socket.close();
      } catch (IOException ignored) {
      }
    }
  }
}
