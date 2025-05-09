package de.sikeller.aqs.taxi.algorithm;

import de.sikeller.aqs.model.*;
import de.sikeller.aqs.model.AlgorithmResult;

import java.util.Collection;
import java.util.Map;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Getter
public class TaxiAlgorithmDoNothing extends AbstractTaxiAlgorithm implements TaxiAlgorithm {
  @Setter private Map<String, Integer> parameters;
  private final String name = "DoNothing";

  @Override
  public SimulationConfiguration getParameters() {
    return new SimulationConfiguration();
  }

  @Override
  public AlgorithmResult nextStep(World world, Collection<Client> waitingClients) {
    // we do not serve any client requests... because we don't want to
    return ok();
  }
}
