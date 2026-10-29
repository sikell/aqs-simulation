package de.sikeller.aqs.model.events;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EventDispatcherTest {
  @Test
  void dispatcherIsThreadLocal() throws Exception {
    EventDispatcher.instance().resetEvents();
    AtomicInteger otherThreadCount = new AtomicInteger();

    Thread thread =
        new Thread(
            () -> {
              EventDispatcher.dispatch(new TestEvent(2));
              otherThreadCount.set(EventDispatcher.instance().getAll().size());
            });

    EventDispatcher.dispatch(new TestEvent(1));
    thread.start();
    thread.join();

    assertEquals(1, EventDispatcher.instance().getAll().size());
    assertEquals(1, otherThreadCount.get());
  }

  private static final class TestEvent extends Event {
    private TestEvent(long currentTime) {
      super(currentTime);
    }

    @Override
    protected String getMessage() {
      return "test";
    }
  }
}
