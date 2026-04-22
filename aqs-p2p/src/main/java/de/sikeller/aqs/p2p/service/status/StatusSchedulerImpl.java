package de.sikeller.aqs.p2p.service.status;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Small wrapper around a single-thread ScheduledExecutorService. */
public class StatusSchedulerImpl implements StatusScheduler {
  private volatile ScheduledExecutorService scheduler;

  @Override
  public void start(Runnable statusTask, long initialDelay, long period, TimeUnit unit) {
    stop();
    scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "p2p-status");
      t.setDaemon(true);
      return t;
    });
    scheduler.scheduleAtFixedRate(statusTask, initialDelay, period, unit);
  }

  @Override
  public void stop() {
    var s = scheduler;
    scheduler = null;
    if (s != null) s.shutdownNow();
  }

  @Override
  public boolean isRunning() { return scheduler != null && !scheduler.isShutdown(); }
}

