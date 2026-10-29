package de.sikeller.aqs.taxi.algorithm.collector;

import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Kapselt den laufzeitnahen Client/Request/Knowledge-Zustand des Collectors. */
@Slf4j
public final class TaxiCollectorRuntimeState {

  public final Map<String, PendingRequest> pending = new ConcurrentHashMap<>();
  private final Map<String, Set<String>> clientIdsByTaxiId = new ConcurrentHashMap<>();

  void clear() {
    pending.clear();
    clientIdsByTaxiId.clear();
  }

  int pendingCount() {
    return pending.size();
  }

  void putPendingRequest(String requestId, String clientName, long lastPublishedStep) {
    if (requestId == null || requestId.isBlank() || clientName == null || clientName.isBlank()) {
      return;
    }
    if (getByRequestId(requestId) != null) {
      throw new IllegalStateException("Request id was already pending");
    }

    PendingRequest pr = new PendingRequest(requestId, clientName, lastPublishedStep);
    PendingRequest previousByClient = pending.put(clientName, pr);
    if (previousByClient != null) {
      throw new IllegalStateException("Client was already pending");
    }
  }

  PendingRequest getByClient(String clientName) {
    return pending.get(clientName);
  }

  PendingRequest getByRequestId(String requestId) {
    if (requestId == null || requestId.isBlank()) {
      return null;
    }
    return pending.values().stream()
        .filter(pendingRequest -> requestId.equals(pendingRequest.requestId()))
        .findFirst()
        .orElse(null);
  }

  boolean markCommittedIfOpen(String requestId, String vehicleNodeId) {
    PendingRequest pendingRequest = getByRequestId(requestId);
    if (pendingRequest == null || vehicleNodeId == null || vehicleNodeId.isBlank()) {
      return false;
    }
    if (pendingRequest.committedVehicleNodeId() != null) {
      return pendingRequest.committedVehicleNodeId().equals(vehicleNodeId);
    }
    pendingRequest.markCommitted(vehicleNodeId);
    return true;
  }

  void removeByClient(String clientName) {
    pending.remove(clientName);
  }

  void removeClientsNotIn(Set<String> activeClientNames) {
    if (activeClientNames == null || activeClientNames.isEmpty()) {
      pending.clear();
      return;
    }
    pending.keySet().removeIf(clientName -> !activeClientNames.contains(clientName));
  }

  Set<String> activeClientNames() {
    return Set.copyOf(pending.keySet());
  }

  public void registerTaxiKnowledge(String taxiId, String clientName) {
    if (taxiId == null || taxiId.isBlank() || clientName == null || clientName.isBlank()) {
      throw new IllegalArgumentException("TaxiId and clientName must not be null or blank");
    }

    // Use concurrent set for taxi's client ids to avoid concurrent modification while iterating
    clientIdsByTaxiId
        .computeIfAbsent(taxiId, ignored -> ConcurrentHashMap.newKeySet())
        .add(clientName);
  }

  public Map<String, Set<String>> taxiKnowledgeSnapshot(Set<String> activeClientNames) {
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

  public static final class PendingRequest {
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
