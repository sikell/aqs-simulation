package de.sikeller.aqs.taxi.algorithm.collector.api;

import java.util.Map;
import java.util.Set;

/**
 * Tracks taxi knowledge observed during the P2P ride assignment protocol.
 * Offers are no longer used; vehicles commit autonomously (first-come-first-serve).
 */
public interface OfferAggregator {

  Map<String, Set<String>> taxiKnowledgeSnapshot(Set<String> clientIds);

  void registerTaxiKnowledge(String taxiDisplayId, String clientName);

  void clearForRequest(String requestId);
}

