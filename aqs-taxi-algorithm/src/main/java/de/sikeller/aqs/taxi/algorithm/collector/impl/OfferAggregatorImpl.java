package de.sikeller.aqs.taxi.algorithm.collector.impl;

import de.sikeller.aqs.taxi.algorithm.collector.api.CollectorRuntimeStateView;
import de.sikeller.aqs.taxi.algorithm.collector.api.OfferAggregator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OfferAggregatorImpl implements OfferAggregator {

  private final CollectorRuntimeStateView runtimeState;
  // simple in-memory store for offers per request (minimal first step)
  private final ConcurrentHashMap<String, List<Offer>> offersByRequest = new ConcurrentHashMap<>();

  public OfferAggregatorImpl(CollectorRuntimeStateView runtimeState) {
    this.runtimeState = runtimeState;
  }

  @Override
  public void recordOffer(String requestId, String clientName, String vehicleNodeId, int etaSeconds, String senderId, Map<String, String> payload) {
    if (requestId == null || requestId.isBlank()) return;
    offersByRequest.computeIfAbsent(requestId, k -> new ArrayList<>()).add(new Offer(vehicleNodeId, etaSeconds, senderId, payload));
    // keep taxi knowledge in runtimeState as before
    runtimeState.registerTaxiKnowledge(vehicleNodeId, clientName);
    log.info("[P2P-COLLECTOR][OfferAggregator] observed offer requestId={} client={} vehicle={} etaSeconds={}", requestId, clientName, vehicleNodeId, etaSeconds);
  }

  @Override
  public Map<String, Set<String>> taxiKnowledgeSnapshot(Set<String> clientIds) {
    return runtimeState.taxiKnowledgeSnapshot(clientIds);
  }

  @Override
  public void registerTaxiKnowledge(String taxiDisplayId, String clientName) {
    runtimeState.registerTaxiKnowledge(taxiDisplayId, clientName);
  }

  @Override
  public void clearForRequest(String requestId) {
    offersByRequest.remove(requestId);
  }

  private static final class Offer {
    final String vehicleNodeId;
    final int etaSeconds;
    final String senderId;
    final Map<String, String> payload;

    Offer(String vehicleNodeId, int etaSeconds, String senderId, Map<String, String> payload) {
      this.vehicleNodeId = vehicleNodeId;
      this.etaSeconds = etaSeconds;
      this.senderId = senderId;
      this.payload = payload;
    }
  }
}

