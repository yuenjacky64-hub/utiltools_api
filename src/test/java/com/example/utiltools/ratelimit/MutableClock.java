package com.example.utiltools.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Test clock whose instant can be advanced deterministically. */
final class MutableClock extends Clock {

    private volatile Instant instant;

    MutableClock(Instant initial) {
        this.instant = initial;
    }

    void advance(Duration duration) {
        this.instant = this.instant.plus(duration);
    }

    @Override
    public long millis() {
        return instant.toEpochMilli();
    }

    @Override
    public Instant instant() {
        return instant;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
