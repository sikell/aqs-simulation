package de.sikeller.aqs.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AlgorithmResult {
  public enum Result {
    /** Nothing to do. */
    STOP,
    /** Next step found, everything ok. */
    FOUND,
    /** Exception occurred!! */
    EXCEPTION
  }

  private final Result status;
  private final String message;
}
