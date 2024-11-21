package de.sikeller.aqs.model.events;

import java.util.List;
import java.util.function.Consumer;

public interface EventList {
  List<Event> getAll();

  void registerListener(Consumer<Event> listener);
}
