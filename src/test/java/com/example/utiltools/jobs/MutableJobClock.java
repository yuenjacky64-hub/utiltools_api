package com.example.utiltools.jobs;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Test-only {@link Clock} whose instant can be advanced by hand, so execution-lease expiry and
 * heartbeat renewal are exercised without sleeping.
 */
final class MutableJobClock extends Clock {

    private Instant now;
    private final ZoneId zone;

    MutableJobClock(Instant start) {
        this(start, ZoneOffset.UTC);
    }

    private MutableJobClock(Instant start, ZoneId zone) {
        this.now = start;
        this.zone = zone;
    }

    void advanceSeconds(long seconds) {
        this.now = now.plusSeconds(seconds);
    }

    void advanceMillis(long millis) {
        this.now = now.plusMillis(millis);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new MutableJobClock(now, newZone);
    }

    @Override
    public Instant instant() {
        return now;
    }
}
