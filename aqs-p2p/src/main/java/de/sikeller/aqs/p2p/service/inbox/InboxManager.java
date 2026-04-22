package de.sikeller.aqs.p2p.service.inbox;

import de.sikeller.aqs.p2p.api.P2PMessage;
import java.util.List;

/** Inbox manager abstraction for buffering and draining incoming messages. */
public interface InboxManager {
  /** Accept an incoming message (called by network callback). */
  void accept(P2PMessage message);

  /** Snapshot current inbox contents in arrival order. */
  List<P2PMessage> snapshot();

  /** Drain and return buffered messages in arrival order (clears the inbox). */
  List<P2PMessage> drain();

  /** Current number of messages in inbox. */
  int size();
}

