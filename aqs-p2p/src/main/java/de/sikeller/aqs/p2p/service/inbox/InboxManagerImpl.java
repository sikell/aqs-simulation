package de.sikeller.aqs.p2p.service.inbox;

import de.sikeller.aqs.p2p.api.P2PMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/** Simple inbox manager using ConcurrentLinkedQueue. */
public class InboxManagerImpl implements InboxManager {
  private final ConcurrentLinkedQueue<P2PMessage> inbox = new ConcurrentLinkedQueue<>();
  private final AtomicInteger size = new AtomicInteger();

  @Override
  public void accept(P2PMessage message) {
    if (message == null) return;
    inbox.add(message);
    size.incrementAndGet();
  }

  @Override
  public List<P2PMessage> snapshot() {
    return new ArrayList<>(inbox);
  }

  @Override
  public List<P2PMessage> drain() {
    List<P2PMessage> drained = new ArrayList<>();
    P2PMessage next;
    while ((next = inbox.poll()) != null) {
      drained.add(next);
      size.decrementAndGet();
    }
    return drained;
  }

  @Override
  public int size() { return size.get(); }
}

