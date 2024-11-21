package de.sikeller.aqs.model.events;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public abstract class Event {
  private final long currentTime;

  protected abstract String getMessage();

  public String printMessage() {
    return "%05d: %s".formatted(currentTime, getMessage());
  }
}
