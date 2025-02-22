package de.sikeller.aqs.model;

import lombok.Value;

@Value
public class OrderNode {
  Client client;
  Position position;
}
