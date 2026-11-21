package com.example.utiltools.contact;

/**
 * Delivery seam for a contact submission attempt.
 *
 * <p>The production wiring is a no-op dispatcher: delivery itself (mailing-list / SMTP / API
 * fan-out) is out of scope for this package, and the form machine's network seam only gains this
 * contract owner here. Tests inject a controllable dispatcher (succeed / fail / hold) to make the
 * state-machine, fencing, and reaper behaviour deterministic.</p>
 *
 * <p><b>Blocking and reclamation (best-effort).</b> Blocking is allowed — the attempt's execution
 * lease clock keeps running while it blocks, and the reaper terminalizes attempts whose lease
 * expires as {@code FAILED} with {@code CONTACT_DELIVERY_LEASE_EXPIRED}, closing the attempt's
 * journal entry exactly once. The reaper then interrupts the dispatch thread once, while the
 * registry lock is held, so the bounded delivery lane is reclaimed:</p>
 *
 * <ul>
 *   <li>An <em>interrupt-responsive</em> dispatcher observes {@link InterruptedException} (or the
 *       interrupt status after returning) and is released from its lane as soon as its lease
 *       lapses; the service treats that as a dispatch failure and fences the late completion out,
 *       so the reaped {@code FAILED} snapshot is never overwritten. Implementations should
 *       propagate {@link InterruptedException} (declared through {@code throws Exception}) rather
 *       than swallow it, and should not clear the interrupt status themselves.</li>
 *   <li>A dispatcher that <em>ignores interrupts</em> cannot be forcibly stopped in-process: the
 *       reaper still returns promptly and the row is still terminal, but that lane stays occupied
 *       until the call returns. The pool size ({@code workerCount}) bounds how many lanes a hostile
 *       dispatcher can hold.</li>
 * </ul>
 *
 * <p>No interrupt is ever delivered to a thread that is not this submission's current dispatch
 * worker, and the interrupt status is cleared before the worker returns to the pool, so a pooled
 * thread always starts its next task clean.</p>
 *
 * @param submissionId the submission being delivered
 * @param generation the 1-based attempt number; a completion carrying a generation that is no
 *                   longer current must be fenced out by the service
 * @throws Exception on any delivery failure; the service rolls the attempt back to a retryable
 *                   {@code FAILED} state with a bounded error code
 */
@FunctionalInterface
public interface ContactDispatcher {

    void dispatch(String submissionId, int generation) throws Exception;
}
