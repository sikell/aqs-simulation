package de.sikeller.aqs.model.events;

import de.sikeller.aqs.model.Client;
import lombok.Builder;
import lombok.Getter;

@Getter
public class EventClientFinished extends Event {
  private final Client client;
  private final long travelTime;

  @Builder
  public EventClientFinished(long currentTime, Client client) {
    super(currentTime);
    this.client = client;
    this.travelTime = currentTime;
  }

  @Override
  protected String getMessage() {
    return "Client %s arrived at target after %d".formatted(client.getName(), travelTime);
  }
}
