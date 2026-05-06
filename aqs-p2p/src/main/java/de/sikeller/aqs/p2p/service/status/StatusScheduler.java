package de.sikeller.aqs.p2p.service.status;

/** Simple scheduled status task abstraction. */
public interface StatusScheduler {
  void start(
      Runnable statusTask, long initialDelay, long period, java.util.concurrent.TimeUnit unit);

  void stop();

  boolean isRunning();
}
