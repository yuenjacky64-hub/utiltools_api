package com.example.utiltools.contact;

/**
 * Delivery seam for a contact submission attempt.
 *
 * <p>The production wiring is a no-op dispatcher: delivery itself (mailing-list / SMTP / API
 * fan-out) is out of scope for this package, and the form machine's network seam only gains this
 * contract owner here. Tests inject a controllable dispatcher (succeed / fail / hold) to make the
 * state-machine, fencing, and reaper behaviour deterministic. Blocking is allowed — the attempt's
 * execution lease clock keeps running while it blocks, and the reaper terminalizes attempts whose
 * lease expires.</p>
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
