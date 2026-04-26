package de.sikeller.aqs.taxi.algorithm.collector.api;

import java.util.Map;

/**
 * Aggregates offers observed for published requests.
 * Minimal interface for the first extraction step.
 */
public interface OfferAggregator {

  void recordOffer(
      String requestId,
      String clientName,
      String vehicleNodeId,
      int etaSeconds,
      String senderId,
      Map<String, String> payload);

  Map<String, java.util.Set<String>> taxiKnowledgeSnapshot(java.util.Set<String> clientIds);

  void registerTaxiKnowledge(String taxiDisplayId, String clientName);

  void clearForRequest(String requestId);
}

