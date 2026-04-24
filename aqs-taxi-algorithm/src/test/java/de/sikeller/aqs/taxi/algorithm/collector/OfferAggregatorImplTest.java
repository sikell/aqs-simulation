package de.sikeller.aqs.taxi.algorithm.collector;

import de.sikeller.aqs.taxi.algorithm.collector.impl.OfferAggregatorImpl;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class OfferAggregatorImplTest {

  @Test
  public void recordOffer_registersTaxiKnowledge() {
    TaxiCollectorRuntimeState runtimeState = new TaxiCollectorRuntimeState();
    OfferAggregatorImpl aggregator = new OfferAggregatorImpl(runtimeState);

    String requestId = "r1";
    String clientName = "client-1";
    String vehicleNodeId = "vehicle-1";

    aggregator.recordOffer(requestId, clientName, vehicleNodeId, 30, vehicleNodeId, Map.of());

    var snapshot = runtimeState.taxiKnowledgeSnapshot(Set.of(clientName));
    assertNotNull(snapshot);
    assertTrue(snapshot.containsKey(vehicleNodeId) || snapshot.keySet().stream().anyMatch(k -> k.equals(vehicleNodeId)));
    assertEquals(Set.of(clientName), snapshot.get(vehicleNodeId));
  }
}

