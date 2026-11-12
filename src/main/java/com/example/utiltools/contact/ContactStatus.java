package com.example.utiltools.contact;

import java.util.Set;

/**
 * States of a contact submission's current dispatch attempt.
 *
 * <p>Legal edges are exactly:</p>
 * <ul>
 *   <li>{@code QUEUED -> DISPATCHING} (worker admission, generation stamped, lease begins)</li>
 *   <li>{@code DISPATCHING -> DELIVERED} (dispatcher returned normally)</li>
 *   <li>{@code DISPATCHING -> FAILED} (dispatcher threw, or the execution lease expired and the
 *       reaper terminalized the attempt)</li>
 *   <li>{@code QUEUED -> CANCELLED} (shutdown drain)</li>
 *   <li>{@code DISPATCHING -> CANCELLED} (pre-dispatch withdrawal: only reachable before the
 *       worker enters the dispatcher — the service's {@code dispatchStarted} fence is the runtime
 *       witness; an in-flight dispatch still terminalizes {@code DELIVERED}/{@code FAILED})</li>
 *   <li>{@code FAILED -> QUEUED} (bounded retry: only while the attempt counter is below
 *       {@code maxAttempts}; increments the counter, so {@code QUEUED} is reachable this way only)</li>
 *   <li>{@code DISPATCHING -> DEAD_LETTERED} (a retryable {@code CONTACT_DISPATCH_FAILED} or
 *       {@code CONTACT_DELIVERY_LEASE_EXPIRED} failure that has reached {@code maxAttempts}:
 *       the attempt-ceiling failure is quarantined instead of staying {@code FAILED} forever)</li>
 *   <li>{@code DEAD_LETTERED -> QUEUED} (operator redrive, bounded by
 *       {@code utiltools.contact.max-redrives}; extends {@code maxAttempts} to {@code attempt + 1},
 *       so {@code QUEUED} is reachable this way only)</li>
 * </ul>
 *
 * <p>{@code DELIVERED} and {@code CANCELLED} are terminal: no edge leaves them.
 * {@code DEAD_LETTERED} has exactly one outgoing edge — the operator-only redrive — and is
 * otherwise terminal: {@code cancel()} and {@code retry()} both refuse it with
 * {@code CONTACT_TRANSITION_CONFLICT} (409), and the automatic retry scanner never scans it.
 * Any edge not listed is illegal and must leave the submission, its attempt counter, and its
 * idempotency mapping untouched while surfacing a {@code CONTACT_TRANSITION_CONFLICT} error
 * (409).</p>
 */
public enum ContactStatus {

    QUEUED,
    DISPATCHING,
    DELIVERED,
    FAILED,
    CANCELLED,
    /** Quarantined: a retryable attempt-ceiling failure. Only an operator {@code /redrive}
     *  leaves this state (bounded by {@code utiltools.contact.max-redrives}). */
    DEAD_LETTERED;

    private static final Set<ContactStatus> TERMINAL =
            Set.of(DELIVERED, CANCELLED);

    /**
     * @return true when no *automatic* edge leaves this state; the operator-only redrive edge
     *         leaves {@code DEAD_LETTERED}, and a bounded operator retry re-enters
     *         {@code QUEUED} from {@code FAILED}
     */
    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /**
     * @param from the current state
     * @param to the requested next state
     * @return true when {@code from -> to} is one of the legal edges listed in the class javadoc
     */
    public boolean allows(ContactStatus from, ContactStatus to) {
        return switch (from) {
            case QUEUED -> to == DISPATCHING || to == CANCELLED;
            case DISPATCHING -> to == DELIVERED || to == FAILED || to == DEAD_LETTERED || to == CANCELLED;
            case FAILED -> to == QUEUED; // bounded retry, enforced by the service's attempt counter
            case DEAD_LETTERED -> to == QUEUED; // operator redrive, bounded by max-redrives
            case DELIVERED, CANCELLED -> false;
        };
    }
}
