package de.sikeller.aqs.taxi.algorithm.collector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Kapselt den laufzeitnahen Client/Request/Knowledge-Zustand des Collectors.
 */
final class TaxiCollectorRuntimeState {

  private final Map<String, PendingRequest> pendingByRequestId = new HashMap<>();
  private final Map<String, PendingRequest> pendingByClientName = new HashMap<>();
  private final Map<String, Set<String>> clientIdsByTaxiId = new HashMap<>();
  private final Map<String, Set<String>> taxiIdsByClientId = new HashMap<>();

  void clear() {
    pendingByRequestId.clear();
    pendingByClientName.clear();
    clientIdsByTaxiId.clear();
    taxiIdsByClientId.clear();
  }

  int pendingCount() {
    return pendingByRequestId.size();
  }

  PendingRequest pendingForClient(String clientName) {
    if (clientName == null || clientName.isBlank()) {
      return null;
    }
    return pendingByClientName.get(clientName);
  }

  PendingRequest pendingForRequestId(String requestId) {
    if (requestId == null || requestId.isBlank()) {
      return null;
    }
    return pendingByRequestId.get(requestId);
  }

  void putPendingRequest(String requestId, String clientName, long lastPublishedStep) {
    if (requestId == null || requestId.isBlank() || clientName == null || clientName.isBlank()) {
      return;
    }

    PendingRequest pending = new PendingRequest(requestId, clientName, lastPublishedStep);
    PendingRequest previousByClient = pendingByClientName.put(clientName, pending);
    if (previousByClient != null && !previousByClient.requestId().equals(requestId)) {
      pendingByRequestId.remove(previousByClient.requestId());
    }
    pendingByRequestId.put(requestId, pending);
  }

  void removePendingForClient(String clientName) {
    if (clientName == null || clientName.isBlank()) {
      return;
    }

    PendingRequest pending = pendingByClientName.remove(clientName);
    if (pending != null) {
      pendingByRequestId.remove(pending.requestId());
    }
    removeKnowledgeForClient(clientName);
  }

  Set<String> pendingClientNamesSnapshot() {
    return Set.copyOf(pendingByClientName.keySet());
  }

  Set<String> activeClientNamesSnapshot() {
    return Set.copyOf(pendingByClientName.keySet());
  }

  void registerTaxiKnowledge(String taxiId, String clientName) {
    if (taxiId == null || taxiId.isBlank() || clientName == null || clientName.isBlank()) {
      return;
    }

    boolean added = clientIdsByTaxiId.computeIfAbsent(taxiId, ignored -> new HashSet<>()).add(clientName);
    if (added) {
      taxiIdsByClientId.computeIfAbsent(clientName, ignored -> new HashSet<>()).add(taxiId);
    }
  }

  Map<String, Set<String>> taxiKnowledgeSnapshot(Set<String> activeClientNames) {
    if (activeClientNames == null || activeClientNames.isEmpty()) {
      return Map.of();
    }

    Map<String, Set<String>> snapshot = new LinkedHashMap<>();
    clientIdsByTaxiId.forEach(
        (taxiId, clientIds) -> {
          Set<String> activeKnownClientIds = new HashSet<>();
          for (String clientId : clientIds) {
            if (activeClientNames.contains(clientId)) {
              activeKnownClientIds.add(clientId);
            }
          }
          if (!activeKnownClientIds.isEmpty()) {
            snapshot.put(taxiId, Set.copyOf(activeKnownClientIds));
          }
        });
    return snapshot;
  }

  private void removeKnowledgeForClient(String clientName) {
    Set<String> taxiIds = taxiIdsByClientId.remove(clientName);
    if (taxiIds == null || taxiIds.isEmpty()) {
      return;
    }

    for (String taxiId : taxiIds) {
      Set<String> clientIds = clientIdsByTaxiId.get(taxiId);
      if (clientIds == null) {
        continue;
      }
      clientIds.remove(clientName);
      if (clientIds.isEmpty()) {
        clientIdsByTaxiId.remove(taxiId);
      }
    }
  }

  static final class PendingRequest {
    private final String requestId;
    private final String clientName;
    private final long lastPublishedStep;
    private RequestState state;
    private String committedVehicleNodeId;

    private PendingRequest(String requestId, String clientName, long lastPublishedStep) {
      this.requestId = requestId;
      this.clientName = clientName;
      this.lastPublishedStep = lastPublishedStep;
      this.state = RequestState.PUBLISHED;
    }

    String requestId() {
      return requestId;
    }

    String clientName() {
      return clientName;
    }

    long lastPublishedStep() {
      return lastPublishedStep;
    }

    RequestState state() {
      return state;
    }

    String committedVehicleNodeId() {
      return committedVehicleNodeId;
    }

    boolean isCommitted() {
      return state == RequestState.COMMITTED;
    }

    void markCommitted(String vehicleNodeId) {
      this.committedVehicleNodeId = vehicleNodeId;
      this.state = RequestState.COMMITTED;
    }
  }

  enum RequestState {
    PUBLISHED,
    COMMITTED
  }
}


