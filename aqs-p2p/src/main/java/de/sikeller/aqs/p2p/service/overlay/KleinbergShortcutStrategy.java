package de.sikeller.aqs.p2p.service.overlay;

import de.sikeller.aqs.model.Position;
import de.sikeller.aqs.p2p.api.NodeDescriptor;
import de.sikeller.aqs.p2p.api.P2PSystemProperties;
import de.sikeller.aqs.p2p.service.position.PositionManager;
import de.sikeller.aqs.p2p.service.util.AliasSampler;
import de.sikeller.aqs.p2p.util.P2PGeoUtils;
import de.sikeller.aqs.p2p.util.P2PRunContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

/**
 * Kleinberg-inspired probabilistic long-range shortcut selection.
 *
 * <p>Selects shortcuts by drawing peers with probability proportional to {@code dist⁻ʳ}, where
 * {@code dist} is the geographic distance (or ring offset as fallback) and {@code r} is the
 * clustering exponent (default 2.0). At {@code r = 2} this matches the optimal value for
 * two-dimensional Kleinberg grids, enabling O(log² n) greedy routing.
 *
 * <p>Reference:
 *
 * <ul>
 *   <li>Kleinberg, J. (2000). "The small-world phenomenon: An algorithmic perspective."
 *       <i>Proceedings of the 32nd ACM Symposium on Theory of Computing (STOC)</i>, 163–170.
 *   <li>Kleinberg, J. (2000). "Navigation in a small world." <i>Nature</i>, 406(6798), 845.
 * </ul>
 *
 * <p>Deviations from the original model: nodes are arranged on a ring (sorted peer list) rather
 * than a 2D grid; geographic positions are used for distance when available. Weighted sampling uses
 * the Alias Method – see {@link de.sikeller.aqs.p2p.service.util.AliasSampler}.
 */
public class KleinbergShortcutStrategy implements ShortcutStrategy {
  @Override
  public List<NodeDescriptor> selectShortcuts(
      NodeDescriptor self,
      List<NodeDescriptor> sortedPeers,
      int startIndex,
      Set<String> excludedPeerIds,
      int limit,
      PositionManager positionManager) {
    if (limit <= 0 || sortedPeers == null || sortedPeers.isEmpty()) return List.of();

    int maxOffset = sortedPeers.size();
    Set<String> usedIds = new HashSet<>();
    if (excludedPeerIds != null) usedIds.addAll(excludedPeerIds);

    long globalSeed = P2PRunContext.worldSeed();
    String nodeProbabilityStr =
        System.getProperty(P2PSystemProperties.OVERLAY_SHORTCUT_NODE_PROBABILITY, "1.0").trim();
    double nodeProbability = clamp(parseDoubleOrDefault(nodeProbabilityStr, 1.0), 0.0, 1.0);
    if (nodeProbability < 1.0) {
      List<String> allIds = new ArrayList<>();
      allIds.add(self.id());
      for (NodeDescriptor nd : sortedPeers) if (nd != null && nd.id() != null) allIds.add(nd.id());
      int total = allIds.size();
      int selectCount = (int) Math.round(nodeProbability * total);
      if (selectCount <= 0) return List.of();
      allIds.sort(Comparator.naturalOrder());
      allIds.sort(
          (a, b) ->
              Long.compareUnsigned(
                  Integer.toUnsignedLong(Objects.hash(b, globalSeed)),
                  Integer.toUnsignedLong(Objects.hash(a, globalSeed))));
      Set<String> selected = new HashSet<>();
      for (int i = 0; i < Math.min(selectCount, allIds.size()); i++) selected.add(allIds.get(i));
      if (!selected.contains(self.id())) return List.of();
    }

    String rStr =
        System.getProperty(P2PSystemProperties.OVERLAY_SHORTCUT_KLEINBERG_R, "2.0").trim();
    double r = Math.max(0.0, parseDoubleOrDefault(rStr, 2.0));
    long seed = globalSeed ^ (long) Objects.hash(self.id());
    Random rnd = new Random(seed);

    List<NodeDescriptor> shortcuts = new ArrayList<>();
    List<Integer> candidateIndices = buildCandidateIndices(sortedPeers, usedIds, self.id());
    Map<String, Position> positionsSnapshot = positionManager.snapshotPositions();
    // compute weights
    int m = candidateIndices.size();
    if (m == 0) return shortcuts;
    double[] weights = new double[m];
    double totalWeight = 0.0;
    for (int i = 0; i < m; i++) {
      int idx = candidateIndices.get(i);
      NodeDescriptor candidate = sortedPeers.get(idx);
      double dist;
      Position other = positionsSnapshot.get(candidate.id());
      Position selfPos = positionsSnapshot.get(self.id());
      if (selfPos != null && other != null) {
        dist = P2PGeoUtils.distance(selfPos.getX(), selfPos.getY(), other.getX(), other.getY());
      } else {
        int ringOffset = Math.abs(idx - startIndex);
        ringOffset = Math.min(ringOffset, maxOffset - ringOffset);
        dist = Math.max(1.0, ringOffset);
      }
      double weight = Math.pow(Math.max(1e-6, dist), -r);
      weights[i] = weight;
      totalWeight += weight;
    }
    if (totalWeight <= 0.0) return shortcuts;

    AliasSampler.Table table = AliasSampler.build(weights, totalWeight);
    int attempts = 0;
    int maxAttempts = Math.max(limit * 10, m * 2);
    while (shortcuts.size() < limit && attempts < maxAttempts && shortcuts.size() < m) {
      int chosen = AliasSampler.sample(rnd, table);
      int selectedPeerIdx = candidateIndices.get(chosen);
      NodeDescriptor selected = sortedPeers.get(selectedPeerIdx);
      if (selected != null && usedIds.add(selected.id())) shortcuts.add(selected);
      attempts++;
    }
    return shortcuts;
  }

  private List<Integer> buildCandidateIndices(
      List<NodeDescriptor> sortedPeers, Set<String> excluded, String selfId) {
    List<Integer> candidateIndices = new ArrayList<>();
    for (int idx = 0; idx < sortedPeers.size(); idx++) {
      NodeDescriptor candidate = sortedPeers.get(idx);
      if (candidate == null) continue;
      String cid = candidate.id();
      if (cid == null || cid.isBlank()) continue;
      if (cid.equals(selfId)) continue;
      if (excluded != null && excluded.contains(cid)) continue;
      candidateIndices.add(idx);
    }
    return candidateIndices;
  }

  private static double parseDoubleOrDefault(String s, double defaultVal) {
    try {
      return Double.parseDouble(s);
    } catch (NumberFormatException e) {
      return defaultVal;
    }
  }

  private static double clamp(double v, double min, double max) {
    return Math.max(min, Math.min(max, v));
  }
}
