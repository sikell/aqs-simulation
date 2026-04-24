package de.sikeller.aqs.taxi.algorithm.collector;

import de.sikeller.aqs.p2p.api.P2PMessage;
import de.sikeller.aqs.p2p.api.P2PNetwork;
import de.sikeller.aqs.p2p.api.P2PPayloadKeys;
import de.sikeller.aqs.p2p.service.ClientP2PService;
import de.sikeller.aqs.p2p.service.KeyValuePayload;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Small helper that encapsulates topology scan handling and delegates storage/side-effects
 * back to the caller via callbacks. Keeps a minimal dependency surface (suppliers + callbacks)
 * so the owning collector can remain authoritative for topology storage.
 */
class TopologyManager {
  private static final String PARAM_TOPOLOGY_SCAN_TICKS = "p2pTopologyScanTicks";
  private static final int DEFAULT_TOPOLOGY_SCAN_TICKS = 20;
  private static final String DEFAULT_ROLE = "-";
  private static final String EVENT_TOPOLOGY_RESPONSE_PREFIX = "topology-response-";

  private final Supplier<ClientP2PService> clientNodeSupplier;
  private final Supplier<P2PNetwork> networkSupplier;
  private final Supplier<Map<String, Integer>> parametersSupplier;
  private final LongSupplier stepCounterSupplier;
  private final LongSupplier lastScanAtStepSupplier;
  private final BiConsumer<String, TopologyData> upsertCallback;
  private final BiConsumer<Long, String> lastScanUpdater;
  private final Consumer<String> refreshStatusCallback;

  public TopologyManager(
      Supplier<ClientP2PService> clientNodeSupplier,
      Supplier<P2PNetwork> networkSupplier,
      Supplier<Map<String, Integer>> parametersSupplier,
      LongSupplier stepCounterSupplier,
      LongSupplier lastScanAtStepSupplier,
      BiConsumer<String, TopologyData> upsertCallback,
      BiConsumer<Long, String> lastScanUpdater,
      Consumer<String> refreshStatusCallback) {
    this.clientNodeSupplier = clientNodeSupplier;
    this.networkSupplier = networkSupplier;
    this.parametersSupplier = parametersSupplier;
    this.stepCounterSupplier = stepCounterSupplier;
    this.lastScanAtStepSupplier = lastScanAtStepSupplier;
    this.upsertCallback = upsertCallback;
    this.lastScanUpdater = lastScanUpdater;
    this.refreshStatusCallback = refreshStatusCallback;
  }

  public static final class TopologyData {
    public final String role;
    public final Set<String> neighborIds;
    public final Set<String> shortcutNeighborIds;

    public TopologyData(String role, Set<String> neighborIds, Set<String> shortcutNeighborIds) {
      this.role = role;
      this.neighborIds = neighborIds == null ? Set.of() : Set.copyOf(neighborIds);
      this.shortcutNeighborIds = shortcutNeighborIds == null ? Set.of() : Set.copyOf(shortcutNeighborIds);
    }
  }

  public void handleTopologyScanResponse(P2PMessage message) {
    if (message == null) return;
    Map<String, String> payload = KeyValuePayload.parse(message.payload());
    String nodeId = message.senderId();
    String role = payload.getOrDefault(P2PPayloadKeys.ROLE, DEFAULT_ROLE);
    Set<String> neighbors = parseNeighbors(payload.get(P2PPayloadKeys.NEIGHBORS));
    Set<String> shortcutNeighbors = parseNeighbors(payload.get(P2PPayloadKeys.SHORTCUT_NEIGHBORS));

    upsertCallback.accept(nodeId, new TopologyData(role, neighbors, shortcutNeighbors));
    refreshStatusCallback.accept(EVENT_TOPOLOGY_RESPONSE_PREFIX + nodeId);
  }

  public void requestScanIfDue(boolean force) {
    ClientP2PService clientNode = clientNodeSupplier.get();
    if (clientNode == null) return;

    long step = stepCounterSupplier.getAsLong();
    Map<String, Integer> params = parametersSupplier.get();
    int period = Math.max(1, params.getOrDefault(PARAM_TOPOLOGY_SCAN_TICKS, DEFAULT_TOPOLOGY_SCAN_TICKS));
    // very small guard: avoid re-scanning too frequently unless forced
    if (!force && step - lastScanAtStepSupplier.getAsLong() < period) return;

    String scanId = clientNode.requestTopologyScan();
    lastScanUpdater.accept(step, scanId);
    upsertLocalTopologyView();
  }

  public void upsertLocalTopologyView() {
    ClientP2PService clientNode = clientNodeSupplier.get();
    P2PNetwork network = networkSupplier.get();
    if (clientNode == null || network == null) return;

    // The collector is a CLIENT node, not part of the geo-local vehicle overlay.
    // Reporting overlay neighbors here would be the non-distance ring-walk (CLIENT role
    // skips vehicleRelevant path) which returns ALL vehicles – misleadingly showing a
    // fully-connected topology. We register the collector with an empty neighbor set so
    // it appears in the graph as an isolated node (or is hidden via ShowLocalCollector).
    String localNodeId = clientNode.descriptor().id();
    upsertCallback.accept(localNodeId, new TopologyData(clientNode.descriptor().role().name(), Set.of(), Set.of()));
  }


  private Set<String> parseNeighbors(String value) {
    if (value == null || value.isBlank()) return Set.of();
    return java.util.Arrays.stream(value.split(","))
        .map(String::trim)
        .filter(s -> !s.isBlank())
        .collect(Collectors.toSet());
  }
}

