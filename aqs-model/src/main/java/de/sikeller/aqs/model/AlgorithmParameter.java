package de.sikeller.aqs.model;

/**
 * Represents an algorithm parameter with a name and a default value. The default value is set to 1
 * if not provided.
 */
public record AlgorithmParameter(String name, Integer defaultValue) {
  public AlgorithmParameter(String name) {
    this(name, 1);
  }
}
