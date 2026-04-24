package de.sikeller.aqs.taxi.algorithm.collector.api;

import java.util.Map;
import java.util.Set;

/**
 * Read-only / limited view of the collector runtime state used by extracted components.
 */
public interface CollectorRuntimeStateView {
  void registerTaxiKnowledge(String taxiId, String clientName);

  Map<String, Set<String>> taxiKnowledgeSnapshot(Set<String> activeClientNames);

  Set<String> pendingClientNamesSnapshot();
}

