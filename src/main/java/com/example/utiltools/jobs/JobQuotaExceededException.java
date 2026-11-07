package com.example.utiltools.jobs;

/**
 * A client already holds {@code utiltools.jobs.max-active-jobs-per-client} non-terminal (QUEUED or
 * RUNNING) batch hash jobs, so a further submission or retry for that client is refused.
 *
 * <p>The message deliberately carries only the two bounded integers: no client key, no job id and
 * no payload fragment, so the 429 body cannot be used to enumerate another client's activity.</p>
 */
public class JobQuotaExceededException extends RuntimeException {

    private final int limit;
    private final int activeJobs;

    public JobQuotaExceededException(int limit, int activeJobs) {
        super("Active batch-hash job quota exceeded: activeJobs=" + activeJobs + ", limit=" + limit);
        this.limit = limit;
        this.activeJobs = activeJobs;
    }

    /** The configured per-client ceiling that was reached. */
    public int limit() {
        return limit;
    }

    /** The client's non-terminal job count observed at the refused admission. */
    public int activeJobs() {
        return activeJobs;
    }
}
