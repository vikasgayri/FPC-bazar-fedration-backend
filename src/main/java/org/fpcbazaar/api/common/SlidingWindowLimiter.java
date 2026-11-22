package org.fpcbazaar.api.common;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/** Single-server limiter with bounded memory. One lock also makes cleanup/reset atomic with acquisitions. */
public class SlidingWindowLimiter {
  private static final int MAX_KEYS = 50_000;
  private final int max;
  private final long windowMs;
  private final Clock clock;
  private final Map<String, Deque<Long>> hits = new HashMap<>();
  private long nextCleanup;

  public SlidingWindowLimiter(int max, Duration window, Clock clock) {
    if (max < 1 || window.isNegative() || window.isZero()) throw new IllegalArgumentException("Invalid rate limit");
    this.max = max; this.windowMs = window.toMillis(); this.clock = clock;
  }
  private void prune(Deque<Long> q, long now) {
    while (!q.isEmpty() && now - q.peekFirst() >= windowMs) q.pollFirst();
  }
  private void cleanup(long now) {
    if (now < nextCleanup) return;
    hits.entrySet().removeIf(e -> { prune(e.getValue(), now); return e.getValue().isEmpty(); });
    nextCleanup = now + Math.min(60_000, windowMs);
  }
  public synchronized boolean tryAcquire(String key) {
    long now = clock.millis(); cleanup(now);
    Deque<Long> q = hits.get(key);
    if (q == null) {
      if (hits.size() >= MAX_KEYS) return false;
      q = new ArrayDeque<>(); hits.put(key, q);
    }
    prune(q, now);
    if (q.size() >= max) return false;
    q.addLast(now); return true;
  }
  public synchronized boolean isBlocked(String key) {
    Deque<Long> q = hits.get(key);
    if (q == null) return false;
    prune(q, clock.millis()); return q.size() >= max;
  }
  public synchronized void release(String key) {
    Deque<Long> q = hits.get(key);
    if (q != null) { q.pollLast(); if (q.isEmpty()) hits.remove(key); }
  }
  public synchronized void reset(String key) { hits.remove(key); }
}
