package de.sikeller.aqs.taxi.algorithm.collector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class TaxiCollectorRuntimeStateTest {

  @Test
  void pendingRequestCanBeReadByClientAndRequestId() {
    TaxiCollectorRuntimeState state = new TaxiCollectorRuntimeState();

    state.putPendingRequest("request-1", "client-1", 7L);

    TaxiCollectorRuntimeState.PendingRequest pending = state.getByClient("client-1");
    assertSame(pending, state.getByRequestId("request-1"));
    assertEquals("request-1", pending.requestId());
    assertEquals("client-1", pending.clientName());
    assertEquals(7L, pending.lastPublishedStep());
  }

  @Test
  void duplicateClientOrRequestIdIsRejected() {
    TaxiCollectorRuntimeState state = new TaxiCollectorRuntimeState();
    state.putPendingRequest("request-1", "client-1", 0L);

    assertThrows(
        IllegalStateException.class, () -> state.putPendingRequest("request-2", "client-1", 1L));
    assertThrows(
        IllegalStateException.class, () -> state.putPendingRequest("request-1", "client-2", 1L));
  }

  @Test
  void commitMarkingIsIdempotentForSameVehicleAndRejectsDifferentWinner() {
    TaxiCollectorRuntimeState state = new TaxiCollectorRuntimeState();
    state.putPendingRequest("request-1", "client-1", 0L);

    assertTrue(state.markCommittedIfOpen("request-1", "vehicle-1"));
    assertTrue(state.markCommittedIfOpen("request-1", "vehicle-1"));
    assertFalse(state.markCommittedIfOpen("request-1", "vehicle-2"));

    TaxiCollectorRuntimeState.PendingRequest pending = state.getByRequestId("request-1");
    assertTrue(pending.isCommitted());
    assertEquals("vehicle-1", pending.committedVehicleNodeId());
  }

  @Test
  void removingByClientClearsBothIndexes() {
    TaxiCollectorRuntimeState state = new TaxiCollectorRuntimeState();
    state.putPendingRequest("request-1", "client-1", 0L);

    state.removeByClient("client-1");

    assertNull(state.getByClient("client-1"));
    assertNull(state.getByRequestId("request-1"));
    assertEquals(0, state.pendingCount());
  }

  @Test
  void removeClientsNotInDropsStalePendingRequests() {
    TaxiCollectorRuntimeState state = new TaxiCollectorRuntimeState();
    state.putPendingRequest("request-1", "client-1", 0L);
    state.putPendingRequest("request-2", "client-2", 0L);

    state.removeClientsNotIn(Set.of("client-2"));

    assertNull(state.getByClient("client-1"));
    assertNull(state.getByRequestId("request-1"));
    assertEquals("request-2", state.getByClient("client-2").requestId());
    assertEquals(Set.of("client-2"), state.activeClientNames());
  }
}
