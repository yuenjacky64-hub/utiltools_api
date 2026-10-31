package com.example.utiltools.resilience;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Capacity-bounded, transition-only audit trail. The oldest entry is evicted once the
 * configured capacity is reached, so {@code size() <= capacity()} is an invariant.
 * Timestamps come from an injected {@link Clock} so the package holds no ambient time source.
 */
public final class AuditTrail {

    private final int capacity;
    private final Clock clock;
    private final Deque<AuditEntry> entries = new ArrayDeque<>();
    private long sequence;

    public AuditTrail(int capacity) {
        this(capacity, Clock.systemUTC());
    }

    public AuditTrail(int capacity, Clock clock) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        this.capacity = capacity;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized AuditEntry record(String breaker, CircuitState from, CircuitState to,
                                          CircuitTrigger trigger, String reason) {
        if (entries.size() == capacity) {
            entries.removeFirst();
        }
        sequence++;
        AuditEntry entry = new AuditEntry(sequence, clock.instant(), breaker, from, to, trigger, reason);
        entries.addLast(entry);
        return entry;
    }

    /** Newest-first immutable snapshot, clamped to {@code [0, min(limit, size())]}. */
    public synchronized List<AuditEntry> recent(int limit) {
        int bounded = Math.max(0, Math.min(limit, entries.size()));
        List<AuditEntry> all = new ArrayList<>(entries);
        List<AuditEntry> newestFirst = new ArrayList<>(bounded);
        for (int i = all.size() - bounded; i < all.size(); i++) {
            newestFirst.add(0, all.get(i));
        }
        return List.copyOf(newestFirst);
    }

    public synchronized int size() {
        return entries.size();
    }

    public int capacity() {
        return capacity;
    }
}
