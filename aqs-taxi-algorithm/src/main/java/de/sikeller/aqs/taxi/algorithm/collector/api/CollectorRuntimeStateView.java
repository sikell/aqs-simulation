package de.sikeller.aqs.taxi.algorithm.collector.api;

import java.util.Map;
import java.util.Set;

/**
 * Limited view of the collector runtime state used by extracted components.
 * Provides both read access and targeted write operations (knowledge registration).
 */
public interface CollectorRuntimeStateView {
  void registerTaxiKnowledge(String taxiId, String clientName);

  Map<String, Set<String>> taxiKnowledgeSnapshot(Set<String> activeClientNames);

  Set<String> pendingClientNamesSnapshot();

  void removePendingForClient(String clientName);
}

