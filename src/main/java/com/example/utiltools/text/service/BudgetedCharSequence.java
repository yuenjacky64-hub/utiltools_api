package com.example.utiltools.text.service;

import com.example.utiltools.text.exception.RegexTimeoutException;

import java.time.Clock;

/**
 * Budget-aware {@link CharSequence} wrapper over the replace input.
 *
 * <p>The JDK regex engine cannot be cancelled mid-{@code find()}, so the only guaranteed seam is
 * {@code charAt} on the input sequence. A budget check (a {@code Clock.millis()} read compared
 * against the request deadline) runs every {@code CHECK_EVERY} {@code charAt} calls; when the
 * deadline has passed a {@link RegexTimeoutException} aborts the evaluation cooperatively.
 * Worst-case overshoot past the deadline is one cadence step of wall time — the check compares the
 * clock against the absolute deadline, so a slow machine never extends the budget. The counter is
 * instance-local, so concurrent replace calls never share deadlines or budget state.
 * {@code subSequence} is not budgeted: the engine reaches the input through {@code charAt} on
 * every pass, including the tail append.</p>
 */
final class BudgetedCharSequence implements CharSequence {

    /** Budget check cadence, in {@code charAt} calls. */
    private static final int CHECK_EVERY = 1024;

    private final String delegate;
    private final Clock clock;
    /** Request deadline in {@code Clock} millis, computed once per replace call. */
    private final long deadlineMs;
    private final int timeoutMs;
    /** Per-instance counter: no state is shared between requests. */
    private int charAtCalls;

    BudgetedCharSequence(String delegate, Clock clock, long deadlineMs, int timeoutMs) {
        this.delegate = delegate;
        this.clock = clock;
        this.deadlineMs = deadlineMs;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public char charAt(int index) {
        if (++charAtCalls % CHECK_EVERY == 0 && clock.millis() >= deadlineMs) {
            throw new RegexTimeoutException(timeoutMs);
        }
        return delegate.charAt(index);
    }

    @Override
    public int length() {
        return delegate.length();
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return delegate.subSequence(start, end);
    }

    @Override
    public String toString() {
        return delegate;
    }
}
