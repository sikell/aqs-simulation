package de.sikeller.aqs.p2p.service.strategy;

import java.util.Collection;
import java.util.Optional;

public interface VehicleRequestSelectionStrategy {
  String key();

  Optional<VehicleRequestCandidate> select(Collection<VehicleRequestCandidate> candidates);
}

