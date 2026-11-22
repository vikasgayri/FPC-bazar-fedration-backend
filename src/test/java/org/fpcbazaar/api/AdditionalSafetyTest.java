package org.fpcbazaar.api;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.List;
import org.fpcbazaar.api.common.SlidingWindowLimiter;
import org.junit.jupiter.api.Test;

class AdditionalSafetyTest {
  static class MutableClock extends Clock {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    public ZoneId getZone() { return ZoneOffset.UTC; }
    public Clock withZone(ZoneId zone) { return this; }
    public Instant instant() { return now; }
  }
  @Test void limiterExpiresAtBoundaryAndCleansUpOldIdentities() {
    MutableClock clock = new MutableClock();
    SlidingWindowLimiter limiter = new SlidingWindowLimiter(1, Duration.ofSeconds(1), clock);
    for(int i=0;i<50_000;i++) assertThat(limiter.tryAcquire("key"+i)).isTrue();
    assertThat(limiter.tryAcquire("overflow")).isFalse();
    clock.now=clock.now.plusSeconds(1);
    assertThat(limiter.tryAcquire("overflow")).isTrue();
    assertThat(limiter.tryAcquire("overflow")).isFalse();
  }
  @Test void concurrentAcquisitionsCannotExceedLimit() throws Exception {
    SlidingWindowLimiter limiter = new SlidingWindowLimiter(3, Duration.ofMinutes(1), Clock.systemUTC());
    try(var pool=java.util.concurrent.Executors.newFixedThreadPool(8)) {
      var calls=new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
      for(int i=0;i<40;i++) calls.add(pool.submit(() -> limiter.tryAcquire("same")));
      int accepted=0; for(var call:calls) if(call.get()) accepted++;
      assertThat(accepted).isEqualTo(3);
    }
  }
}
