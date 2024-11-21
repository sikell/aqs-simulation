package de.sikeller.aqs.model.events;

import de.sikeller.aqs.model.Client;
import de.sikeller.aqs.model.Taxi;
import lombok.Builder;
import lombok.Getter;

@Getter
public class EventClientLeaveTaxi extends Event {
  private final Client client;
  private final Taxi taxi;

  @Builder
  public EventClientLeaveTaxi(long currentTime, Client client, Taxi taxi) {
    super(currentTime);
    this.client = client;
    this.taxi = taxi;
  }

  @Override
  protected String getMessage() {
    return "Client %s leave taxi %s".formatted(client.getName(), taxi.getName());
  }
}
