package de.sikeller.aqs.simulation.result;

import de.sikeller.aqs.model.ResultTable;

@FunctionalInterface
public interface SimulationResultSink {
  void accept(ResultTable table);
}

