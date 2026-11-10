package com.example.utiltools.audit;

import com.example.utiltools.audit.service.ReplayHeartbeatScheduler;

import java.time.Duration;

/**
 * Test-only {@link ReplayHeartbeatScheduler} that records the schedule and runs the tick only when
 * the test asks it to, so renewal is exercised deterministically without sleeping.
 */
final class ManualReplayHeartbeatScheduler implements ReplayHeartbeatScheduler {

    private Runnable tick;
    private Duration period;
    private int scheduled;
    private int cancelled;
    private int ticks;

    @Override
    public Handle schedule(Runnable tick, Duration period) {
        this.tick = tick;
        this.period = period;
        this.scheduled++;
        return () -> cancelled++;
    }

    /** Runs the last scheduled tick by hand; a no-op when nothing was scheduled. */
    void tick() {
        Runnable current = tick;
        if (current != null) {
            ticks++;
            current.run();
        }
    }

    /** How many times a tick actually ran (a forced tick after completion still counts). */
    int tickCount() {
        return ticks;
    }

    /** The cadence the service asked for. */
    Duration period() {
        return period;
    }

    int scheduledCount() {
        return scheduled;
    }

    int cancelCount() {
        return cancelled;
    }

    boolean isCancelled() {
        return scheduled > 0 && cancelled >= scheduled;
    }
}
