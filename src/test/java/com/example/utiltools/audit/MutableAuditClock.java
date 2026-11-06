package com.example.utiltools.audit;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Test-only {@link Clock} whose instant can be advanced by hand, so replay lease expiry is
 * exercised without sleeping.
 */
final class MutableAuditClock extends Clock {

    private Instant now;
    private final ZoneId zone;

    MutableAuditClock(Instant start) {
        this(start, ZoneOffset.UTC);
    }

    private MutableAuditClock(Instant start, ZoneId zone) {
        this.now = start;
        this.zone = zone;
    }

    void advanceSeconds(long seconds) {
        this.now = now.plusSeconds(seconds);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new MutableAuditClock(now, newZone);
    }

    @Override
    public Instant instant() {
        return now;
    }
}
