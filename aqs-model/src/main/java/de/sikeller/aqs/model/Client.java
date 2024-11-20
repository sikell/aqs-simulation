package de.sikeller.aqs.model;

import lombok.Data;

@Data
public class Client {
  private final String name;
  private Position position;
}
