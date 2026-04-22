package de.sikeller.aqs.p2p.service.overlay;

import de.sikeller.aqs.p2p.api.NodeDescriptor;
import de.sikeller.aqs.p2p.api.P2PSystemProperties;
import de.sikeller.aqs.p2p.service.position.PositionManager;
import de.sikeller.aqs.p2p.service.position.Position;
import de.sikeller.aqs.p2p.service.util.AliasSampler;
import de.sikeller.aqs.p2p.util.P2PGeoUtils;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.Comparator;
import java.util.regex.Pattern;

/**
 * Kleinberg-like probabilistic long-range shortcut selection.
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

    long globalSeed = Long.getLong("worldSeed", 0L);
    String nodeProbabilityStr = System.getProperty(P2PSystemProperties.OVERLAY_SHORTCUT_NODE_PROBABILITY, "1.0").trim();
    double nodeProbability = clamp(parseDoubleOrDefault(nodeProbabilityStr, 1.0), 0.0, 1.0);
    if (nodeProbability < 1.0) {
      List<String> allIds = new ArrayList<>();
      allIds.add(self.id());
      for (NodeDescriptor nd : sortedPeers) if (nd != null && nd.id() != null) allIds.add(nd.id());
      int total = allIds.size();
      int selectCount = (int) Math.round(nodeProbability * total);
      if (selectCount <= 0) return List.of();
      allIds.sort(Comparator.naturalOrder());
      allIds.sort((a, b) -> Long.compareUnsigned(Integer.toUnsignedLong(Objects.hash(b, globalSeed)), Integer.toUnsignedLong(Objects.hash(a, globalSeed))));
      Set<String> selected = new HashSet<>();
      for (int i = 0; i < Math.min(selectCount, allIds.size()); i++) selected.add(allIds.get(i));
      if (!selected.contains(self.id())) return List.of();
    }

    String rStr = System.getProperty(P2PSystemProperties.OVERLAY_SHORTCUT_KLEINBERG_R, "2.0").trim();
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
        dist = P2PGeoUtils.distance(selfPos.x(), selfPos.y(), other.x(), other.y());
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

  private List<Integer> buildCandidateIndices(List<NodeDescriptor> sortedPeers, Set<String> excluded, String selfId) {
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

  // Numeric pattern: optional sign, digits with optional decimal point and optional exponent
  private static final Pattern NUMERIC = Pattern.compile("[-+]?\\d*\\.?\\d+([eE][-+]?\\d+)?");

  private static double parseDoubleOrDefault(String s, double defaultVal) {
    if (s == null || s.isBlank()) return defaultVal;
    if (!NUMERIC.matcher(s).matches()) return defaultVal;
    return Double.parseDouble(s);
  }

  private static double clamp(double v, double min, double max) {
    return Math.max(min, Math.min(max, v));
  }
}

