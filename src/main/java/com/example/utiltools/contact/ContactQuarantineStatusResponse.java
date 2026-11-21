package com.example.utiltools.contact;

/**
 * Read-only aggregate of the contact dead-letter/redrive budgets (REQ-03 / REQ-04), the
 * contact-side sibling of {@code AuditJournalService.quarantineStatus()}.
 *
 * <p>{@code maxRedrives} is the effective {@code utiltools.contact.max-redrives} budget
 * ({@code 0} disables the operator redrive). {@code deadLetteredCount} counts the rows quarantined
 * as {@link ContactStatus#DEAD_LETTERED} — the attempt-ceiling failures whose only automatic-safe
 * exit is gone — and {@code redriveExhaustedCount} is the subset whose redrive budget is spent
 * ({@code redrives == maxRedrives}), i.e. the rows no operator redrive can move any more.
 * {@code pendingCount} counts the {@code QUEUED} and {@code DISPATCHING} rows, and
 * {@code pendingDispatchGate} is the {@code workerCount + queueCapacity} admission gate those rows
 * are bounded by. {@code retainedSubmissionCount} is the registry size that denominates the counts.
 *
 * <p>The body is counts only: no submission id, name, email, message, attempt or lease deadline is
 * carried here and no journal entry is opened for it, so the projection cannot widen the exposure
 * of the submission payload.</p>
 */
public record ContactQuarantineStatusResponse(
        int maxRedrives,
        int deadLetteredCount,
        int redriveExhaustedCount,
        int pendingCount,
        int pendingDispatchGate,
        int retainedSubmissionCount) {
}
