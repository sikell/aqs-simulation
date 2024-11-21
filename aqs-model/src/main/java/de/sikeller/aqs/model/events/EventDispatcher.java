package de.sikeller.aqs.model.events;

import lombok.extern.slf4j.Slf4j;

import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;

@Slf4j
public class EventDispatcher implements EventList {
  private static final EventDispatcher instance = new EventDispatcher();
  private final List<Event> eventList = new LinkedList<>();
  private final List<Consumer<Event>> listeners = new LinkedList<>();

  public void dispatchEvent(Event event) {
    eventList.add(event);
    listeners.forEach(l -> l.accept(event));
  }

  public void print() {
    eventList.forEach(e -> log.info("{}", e.printMessage()));
  }

  public List<Event> getAll() {
    return new LinkedList<>(eventList);
  }

  public void registerListener(Consumer<Event> listener) {
    this.listeners.add(listener);
  }

  public static EventDispatcher instance() {
    return instance;
  }

  public static void dispatch(Event event) {
    instance.dispatchEvent(event);
  }
}
