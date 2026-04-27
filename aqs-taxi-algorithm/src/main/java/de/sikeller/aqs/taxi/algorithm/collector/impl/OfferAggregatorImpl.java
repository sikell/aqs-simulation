package de.sikeller.aqs.taxi.algorithm.collector.impl;

import de.sikeller.aqs.taxi.algorithm.collector.api.CollectorRuntimeStateView;
import de.sikeller.aqs.taxi.algorithm.collector.api.OfferAggregator;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OfferAggregatorImpl implements OfferAggregator {

  private final CollectorRuntimeStateView runtimeState;

  public OfferAggregatorImpl(CollectorRuntimeStateView runtimeState) {
    this.runtimeState = runtimeState;
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
    // nothing to clean up – no in-flight offers stored since vehicles commit autonomously
  }
}

