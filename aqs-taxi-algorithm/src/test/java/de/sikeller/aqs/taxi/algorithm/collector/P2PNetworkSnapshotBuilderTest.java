package de.sikeller.aqs.taxi.algorithm.collector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.sikeller.aqs.model.P2PNetworkNodeSnapshot;
import de.sikeller.aqs.model.P2PNetworkSnapshot;
import de.sikeller.aqs.p2p.api.NodeDescriptor;
import de.sikeller.aqs.p2p.api.NodeRole;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class P2PNetworkSnapshotBuilderTest {

  private final P2PNetworkSnapshotBuilder builder = new P2PNetworkSnapshotBuilder();

  @Test
  void buildUsesExpectedRolePriority() {
    Map<String, P2PNetworkSnapshotBuilder.TopologyViewData> views =
        Map.of(
            "peer-1",
            new P2PNetworkSnapshotBuilder.TopologyViewData(
                "peer-1", "CLIENT", Set.of("x"), Set.of()),
            "orphan",
            new P2PNetworkSnapshotBuilder.TopologyViewData(
                "orphan", "VEHICLE", Set.of(), Set.of()));

    P2PNetworkSnapshot snapshot =
        builder.build(
            "local-1", "CLIENT", List.of(new NodeDescriptor("peer-1", NodeRole.VEHICLE)), views);

    Map<String, String> roleByNode =
        snapshot.nodes().stream()
            .collect(
                Collectors.toMap(
                    P2PNetworkNodeSnapshot::id, P2PNetworkNodeSnapshot::role));

    assertEquals("CLIENT", roleByNode.get("local-1"));
    assertEquals("VEHICLE", roleByNode.get("peer-1"));
    assertEquals("VEHICLE", roleByNode.get("orphan"));
    assertEquals("UNKNOWN", roleByNode.get("x"));
  }

  @Test
  void buildDeduplicatesEdgesAndMergesShortcutFlags() {
    Map<String, P2PNetworkSnapshotBuilder.TopologyViewData> views =
        Map.of(
            "a",
            new P2PNetworkSnapshotBuilder.TopologyViewData(
                "a", "CLIENT", Set.of("b"), Set.of("b")),
            "b",
            new P2PNetworkSnapshotBuilder.TopologyViewData(
                "b", "VEHICLE", Set.of("a", "c"), Set.of()));

    P2PNetworkSnapshot snapshot = builder.build("a", "CLIENT", List.of(), views);

    assertEquals(2, snapshot.edges().size());
    assertEquals("a", snapshot.edges().get(0).fromNodeId());
    assertEquals("b", snapshot.edges().get(0).toNodeId());
    assertTrue(snapshot.edges().get(0).shortcut());
    assertEquals("b", snapshot.edges().get(1).fromNodeId());
    assertEquals("c", snapshot.edges().get(1).toNodeId());
    assertFalse(snapshot.edges().get(1).shortcut());
  }

  @Test
  void buildAliasesNodeIdsForLanDisplay() {
    Map<String, P2PNetworkSnapshotBuilder.TopologyViewData> views =
        Map.of(
            "vehicle-host-a",
            new P2PNetworkSnapshotBuilder.TopologyViewData(
                "vehicle-host-a", "VEHICLE", Set.of("vehicle-host-b"), Set.of("vehicle-host-b")));

    P2PNetworkSnapshot snapshot =
        builder.build(
            "collector-1",
            "CLIENT",
            List.of(new NodeDescriptor("vehicle-host-a", NodeRole.VEHICLE)),
            views,
            Map.of("vehicle-host-a", "t0", "vehicle-host-b", "t1"));

    Set<String> nodeIds =
        snapshot.nodes().stream().map(P2PNetworkNodeSnapshot::id).collect(Collectors.toSet());
    assertTrue(nodeIds.contains("t0"));
    assertTrue(nodeIds.contains("t1"));
    assertFalse(nodeIds.contains("vehicle-host-a"));
    assertEquals("t0", snapshot.edges().get(0).fromNodeId());
    assertEquals("t1", snapshot.edges().get(0).toNodeId());
    assertTrue(snapshot.edges().get(0).shortcut());
  }

  @Test
  void topologyViewNormalizesShortcutsToKnownNeighborsOnly() {
    P2PNetworkSnapshotBuilder.TopologyViewData view =
        new P2PNetworkSnapshotBuilder.TopologyViewData(
            "node-1", "CLIENT", Set.of("node-2"), Set.of("node-2", "node-3"));

    assertEquals(Set.of("node-2"), view.shortcutNeighborIds());
  }
}

