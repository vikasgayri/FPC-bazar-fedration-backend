package org.fpcbazaar.api.common;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Small in-memory limiter (single server). Allows {@code max} events per key inside {@code window}. */
public class SlidingWindowLimiter {
  private final int max;
  private final long windowMs;
  private final Clock clock;
  private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

  public SlidingWindowLimiter(int max, Duration window, Clock clock) {
    this.max = max;
    this.windowMs = window.toMillis();
    this.clock = clock;
  }

  /** Records an attempt; returns false if the key is over the limit. */
  public boolean tryAcquire(String key) {
    long now = clock.millis();
    Deque<Long> q = hits.computeIfAbsent(key, (k) -> new ArrayDeque<>());
    synchronized (q) {
      while (!q.isEmpty() && now - q.peekFirst() > windowMs) q.pollFirst();
      if (q.size() >= max) return false;
      q.addLast(now);
      return true;
    }
  }

  /** True if the key is currently over the limit, without recording anything. */
  public boolean isBlocked(String key) {
    long now = clock.millis();
    Deque<Long> q = hits.get(key);
    if (q == null) return false;
    synchronized (q) {
      while (!q.isEmpty() && now - q.peekFirst() > windowMs) q.pollFirst();
      return q.size() >= max;
    }
  }

  public void reset(String key) { hits.remove(key); }
}
