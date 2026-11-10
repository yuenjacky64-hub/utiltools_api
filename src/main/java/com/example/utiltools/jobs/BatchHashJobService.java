package com.example.utiltools.jobs;

import com.example.utiltools.audit.model.OperationType;
import com.example.utiltools.audit.service.AuditJournalService;
import com.example.utiltools.common.RequestContext;
import com.example.utiltools.crypto.dto.HashRequest;
import com.example.utiltools.crypto.dto.HashResponse;
import com.example.utiltools.crypto.service.CryptoService;
import com.example.utiltools.ratelimit.ClientKeyResolver;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The single owner of all mutable job state.
 *
 * <p>Jobs are executed by a bounded {@link ThreadPoolExecutor} backed by an
 * {@link ArrayBlockingQueue} with an {@link ThreadPoolExecutor.AbortPolicy}, so saturation is
 * observable as a {@link JobQueueFullException} instead of blocking a request thread or growing
 * the heap. Every accepted job moves QUEUED -> RUNNING -> COMPLETED|FAILED exactly once, or
 * QUEUED -> CANCELLED when it is withdrawn before a worker admits it; a FAILED job may be requeued
 * as FAILED -> QUEUED while its attempt count is below the configured maximum. An optional
 * idempotency key maps to exactly one job and one execution, and stored results keep the input item
 * order.</p>
 *
 * <p>Admission to that bounded executor is <em>fair per client</em>. An accepted attempt is not handed
 * to the pool by the submitting thread: it is appended to the FIFO lane of its normalized client key
 * and a single dispatcher thread hands attempts over in round-robin order across the clients that have
 * queued work, so one client can never occupy both workers plus the whole queue while other clients
 * wait. Dispatch is a <em>round barrier</em> round robin: the dispatcher walks the ring of clients with
 * queued work, serving each at most once per round, and only opens the next round when no client with a
 * pending lane is still unserved in the current one. Because a client that enqueues mid-round joins that
 * round at the tail of the ring (its cursor starts at "not yet served"), the guarantee is structural:
 * while several clients have queued work, no client is dispatched twice before every other client with
 * queued work has been dispatched once, so a client waits behind at most
 * {@code (clients-with-queued-work - 1)} dispatches, however deep any single lane grows and whatever the
 * dispatch history of the clients involved. Fairness
 * changes <em>order only</em>: the global bound stays {@code worker-count + queue-capacity} accepted
 * (queued or running) attempts, the per-client quota, idempotency, cancellation, retry, deadline and
 * retention semantics are untouched, and a submission that would exceed the global bound is still
 * refused with a {@link JobQueueFullException} that leaves no job row, no idempotency mapping, no
 * attempt correlation and no quota or capacity increment behind.</p>
 *
 * <p>Each execution attempt carries a generation equal to its 1-based attempt number. A worker
 * captures its generation at admission and writes RUNNING, COMPLETED or FAILED only while
 * {@code job.attempt} still equals it, so a late worker from a superseded attempt cannot overwrite
 * the retried attempt's status, results or error.</p>
 *
 * <p>Each admitted attempt also holds an execution lease: the worker stamps
 * {@code lastHeartbeatAt} and {@code executionLeaseExpiresAt} at admission and renews the lease
 * before hashing every item. A scheduled reaper moves a RUNNING job whose lease has lapsed to
 * FAILED with error {@code EXECUTION_LEASE_EXPIRED}, so a worker stalled inside
 * {@code CryptoService.hash} can no longer leave a job RUNNING forever. Reaping reuses the
 * generation fence: a reaped attempt keeps its attempt number but is no longer RUNNING, so a late
 * worker from that attempt writes nothing, and the FAILED job stays retryable.</p>
 *
 * <p>Progress is checkpointed per item. A private ordered prefix of hashed {@link HashResponse}
 * values lives on the job, and each successful hash is committed to it only while the worker still
 * owns the job ({@code job.attempt == generation && job.status == RUNNING}), so a fenced, cancelled,
 * reaped or superseded worker never appends. An attempt resumes at the prefix's next index, and a
 * {@code FAILED -> QUEUED} retry keeps the prefix, so a transient fault late in a batch no longer
 * rehashes everything before it. The prefix is published as {@code results} and cleared only on
 * {@code COMPLETED}; {@code completedItems} reports its size while the job is not completed, and a
 * {@code CANCELLED} job clears it so a withdrawal never retains partial digests.</p>
 *
 * <p>Every <em>started</em> attempt is journalled exactly once as a {@code BATCH_HASH} entry. The
 * correlation id ({@link RequestContext}) is captured on the caller thread at
 * {@link #submit(BatchHashRequest)} / {@link #retry(String)} time and carried on the attempt, then
 * installed on the worker thread for the duration of the attempt and cleared in a {@code finally}
 * block, so a reused pool thread never leaks one job's {@code X-Request-Id} into the next. The
 * journal entry is opened at worker admission and closed in the same job-monitor section that
 * stamps the attempt's terminal status (worker completion, worker failure, or reaper fencing), so
 * the audit state can never disagree with the job snapshot; every journal call is best effort and
 * cannot move a job's status, results or error.</p>
 */
@Service
public class BatchHashJobService {

    /** Error recorded on a RUNNING attempt whose execution lease lapsed without a renewal. */
    public static final String EXECUTION_LEASE_EXPIRED = "EXECUTION_LEASE_EXPIRED";

    /**
     * Error recorded on a QUEUED or RUNNING attempt whose total-life deadline passed before the batch
     * finished. Distinct from {@link #EXECUTION_LEASE_EXPIRED}: a live lease does not excuse a
     * deadline, and the checkpoint prefix is kept so a retry resumes where the deadline cut in.
     */
    public static final String JOB_DEADLINE_EXCEEDED = "JOB_DEADLINE_EXCEEDED";

    /** Error recorded on a non-terminal job the {@code @PreDestroy} drain failed on the way down. */
    public static final String JOB_SHUTDOWN = "JOB_SHUTDOWN";

    /** Grace window the drain waits for in-flight work when the property is not configured. */
    public static final long DEFAULT_SHUTDOWN_GRACE_SECONDS = 30L;

    /** Per-client ceiling on non-terminal jobs when the property is not configured. */
    static final int DEFAULT_MAX_ACTIVE_JOBS_PER_CLIENT = 20;

    /** Name of the single fair-admission dispatcher thread (daemon: it never keeps the JVM alive). */
    private static final String DISPATCHER_THREAD_NAME = "batch-hash-dispatcher";

    /** Upper bound on the configured default deadline, mirroring the request's 1..3600 range. */
    private static final long MAX_DEADLINE_SECONDS = 3600;

    /** Hard ceiling on retained terminal job rows when the property is not configured. */
    static final int DEFAULT_MAX_RETAINED_JOBS = 1000;

    private static final long DEFAULT_EXECUTION_LEASE_SECONDS = 30;
    private static final long DEFAULT_EXECUTION_REAPER_INTERVAL_MS = 5000;
    private static final String BATCH_HASH_FAILED = "BATCH_HASH_FAILED";
    /**
     * Error recorded on a batch that hashed some items and failed others: the attempt is FAILED, no
     * partial result list is published, and both per-item sets stay on the job so a retry resumes
     * from the checkpoint instead of losing the items that did hash.
     */
    private static final String BATCH_ITEM_HASH_FAILED = "BATCH_ITEM_HASH_FAILED";
    /** Bound on the journaled detail of one item failure, mirroring {@link #MAX_AUDIT_TEXT}. */
    private static final int ITEM_ERROR_TEXT = 200;
    /** Cap on the per-item error map a journaled outcome may carry (a partial report, not a dump). */
    private static final int MAX_ITEM_ERROR_ENTRIES = 20;
    /** Bounded, payload-free detail of a heartbeat row; compile-time constant (27 chars, far under 200). */
    private static final String HEARTBEAT_DETAIL = "lease renewed via heartbeat";
    /** Result marker that distinguishes a lease-renewal row from an attempt row. */
    private static final String HEARTBEAT_STATUS = "HEARTBEAT";
    /** Bound shared by every client-controlled string that enters the audit journal. */
    private static final int MAX_AUDIT_TEXT = 200;

    private static final Logger log = LoggerFactory.getLogger(BatchHashJobService.class);

    /** Default number of entries returned by {@link #listJobs(JobStatus, int)}. */
    public static final int DEFAULT_LIST_LIMIT = 20;

    /** Hard ceiling on the number of entries returned by {@link #listJobs(JobStatus, int)}. */
    public static final int MAX_LIST_LIMIT = 100;

    /** Newest first: createdAt descending, jobId ascending as the deterministic tie-breaker. */
    private static final Comparator<MutableJob> LIST_ORDER =
            Comparator.comparing((MutableJob job) -> job.createdAt).reversed()
                    .thenComparing(job -> job.jobId);

    /** Exact inverse of LIST_ORDER (tie-break included); used only as the bounded-heap eviction order. */
    private static final Comparator<MutableJob> LIST_ORDER_INVERSE = LIST_ORDER.reversed();

    /**
     * Oldest {@code completedAt} first, {@code jobId} ascending as the deterministic tie-break, so
     * repeated passes over the same registry pick the same victims in the same order. Only terminal
     * rows that carry a {@code completedAt} become candidates (Plan §4.2), so no row passed to this
     * comparator is ever null there.
     */
    private static final Comparator<MutableJob> EVICTION_ORDER =
            Comparator.comparing((MutableJob job) -> job.completedAt)
                    .thenComparing(job -> job.jobId);

    private final CryptoService cryptoService;
    private final ThreadPoolExecutor executor;
    private final int workerCount;
    private final int queueCapacity;
    private final long retentionSeconds;
    private final int maxAttempts;
    private final long executionLeaseSeconds;
    private final long executionReaperIntervalMs;
    private final long shutdownGraceSeconds;
    private final int maxActiveJobsPerClient;
    /** Server-side default bound applied when a request omits {@code deadlineSeconds}; 0 disables it. */
    private final long defaultDeadlineSeconds;

    private final int maxRetainedJobs;

    private final Clock clock;

    /**
     * Serializes concurrent drain callers only. Workers never take it, so it cannot deadlock with
     * the pool, and it is always taken first (never while a job monitor is held).
     */
    private final Object shutdownMonitor = new Object();

    /** Null means auditing is disabled (test slices and direct construction). */
    private volatile AuditJournalService journal;

    private final ConcurrentHashMap<String, MutableJob> jobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> idempotencyIndex = new ConcurrentHashMap<>();
    private final Object registryLock = new Object();

    /**
     * Live (QUEUED or RUNNING) job count per client key. Guarded by {@link #registryLock}; an entry
     * is removed at zero, so the map stays bounded by the number of non-terminal jobs
     * ({@code worker-count + queue-capacity}) and not by the client population.
     */
    private final Map<String, Integer> activeJobsByClient = new HashMap<>();

    /**
     * Fair-admission state (REQ-01). {@code lanes} holds the FIFO of queued attempts per normalized
     * client key, {@code laneOrder} is the dispatch ring over the clients that currently have queued
     * work, and {@code servedThisRound} is the set of clients already served in the current round. The
     * dispatcher serves a client only while it is <em>not</em> in {@code servedThisRound}, so every
     * client with pending work is served exactly once per round and a client waits behind at most
     * {@code clients-with-queued-work - 1} dispatches. Both containers are guarded by
     * {@link #dispatchLock}. The set is pruned wholesale whenever the round closes, which happens both
     * once every client with a pending lane has been served and as soon as the ring is empty (nothing
     * queued at all, so no queued client can be starved by the marks); a long job keeping a worker busy
     * therefore cannot accumulate one key per dispatch. The remaining case - a ring that never empties
     * because one client keeps a lane non-empty while new clients stream in - is capped: once the set
     * reaches {@code worker-count + queue-capacity} keys the round is closed as well, which restarts
     * selection at the ring head (the longest-waiting client). The bookkeeping is therefore bounded by
     * one key per unit of global capacity - never by the client population, by dispatch count or by any
     * per-client history (Plan §5).
     */
    private final Map<String, Deque<LaneEntry>> lanes = new HashMap<>();
    private final Deque<String> laneOrder = new ArrayDeque<>();
    private final Set<String> servedThisRound = new HashSet<>();

    /**
     * Attempts handed to the pool and not yet finished, guarded by {@link #dispatchLock}. The
     * dispatcher only hands off while this is below {@code worker-count}, so the pool's own queue
     * stays empty and the per-client lanes (not the pool's FIFO) decide which client runs next. The
     * global bound is unchanged: {@code reservedCapacity <= worker-count + queue-capacity}, so the
     * lane depth can never exceed {@code queue-capacity} (REQ-01, REQ-02).
     */
    private int dispatchedInFlight;

    /**
     * Guards the fair-admission state and the dispatcher's wait/notify. It is the innermost lock of
     * the class: {@code registryLock} and a job monitor may both be held when it is taken, while the
     * dispatcher never takes either of those (or a job monitor) while it holds it, so no cycle exists.
     * The executor hand-off always happens <em>outside</em> this lock.
     */
    private final Object dispatchLock = new Object();

    /**
     * Accepted (queued or running) attempts currently holding a global capacity unit. Guarded by
     * {@link #registryLock} and released exactly once with the per-client claim it was taken with, so
     * the global bound survives fair admission unchanged (REQ-02).
     */
    private int reservedCapacity;

    /** Set by the drain while {@link #dispatchLock} is held; a set flag stops every new hand-off. */
    private boolean shutdownRequested;

    /** The single fair-admission dispatcher; daemon so it never keeps the JVM alive. */
    private final Thread dispatcher;

    /**
     * Compatibility overload for callers (and tests) that build the service directly: the system
     * clock and the default lease and reaper cadence are used.
     */
    public BatchHashJobService(CryptoService cryptoService, int workerCount, int queueCapacity,
            long retentionSeconds, int maxAttempts) {
        this(cryptoService, workerCount, queueCapacity, retentionSeconds, maxAttempts,
                DEFAULT_EXECUTION_LEASE_SECONDS, DEFAULT_EXECUTION_REAPER_INTERVAL_MS,
                DEFAULT_MAX_ACTIVE_JOBS_PER_CLIENT, Clock.systemUTC());
    }

    /** The checkpoint projection used by the audit outcome; mirrors {@link #snapshot(MutableJob)}. */
    private static int completedItems(MutableJob job) {
        return switch (job.status) {
            case COMPLETED -> job.items.size();
            case CANCELLING, CANCELLED -> 0; // a cancelled job never exposes a prefix
            default -> job.checkpoint.size();
        };
    }

    /**
     * Compatibility overload for callers (and tests) that pass an explicit clock but not the
     * execution lease / reaper cadence.
     */
    public BatchHashJobService(CryptoService cryptoService, int workerCount, int queueCapacity,
            long retentionSeconds, int maxAttempts, Clock clock) {
        this(cryptoService, workerCount, queueCapacity, retentionSeconds, maxAttempts,
                DEFAULT_EXECUTION_LEASE_SECONDS, DEFAULT_EXECUTION_REAPER_INTERVAL_MS,
                DEFAULT_MAX_ACTIVE_JOBS_PER_CLIENT, clock);
    }

    /**
     * The Spring-selected constructor: every knob is bound here, including the {@code @PreDestroy}
     * drain's grace window.
     */
    @Autowired
    public BatchHashJobService(CryptoService cryptoService,
            @Value("${utiltools.jobs.worker-count:2}") int workerCount,
            @Value("${utiltools.jobs.queue-capacity:100}") int queueCapacity,
            @Value("${utiltools.jobs.retention-seconds:300}") long retentionSeconds,
            @Value("${utiltools.jobs.max-attempts:3}") int maxAttempts,
            @Value("${utiltools.jobs.execution-lease-seconds:30}") long executionLeaseSeconds,
            @Value("${utiltools.jobs.execution-reaper-interval-ms:5000}") long executionReaperIntervalMs,
            @Value("${utiltools.jobs.shutdown-grace-seconds:30}") long shutdownGraceSeconds,
            @Value("${utiltools.jobs.max-active-jobs-per-client:20}") int maxActiveJobsPerClient,
            @Value("${utiltools.jobs.default-deadline-seconds:0}") long defaultDeadlineSeconds,
            @Value("${utiltools.jobs.max-retained-jobs:1000}") int maxRetainedJobs,
            Clock clock) {
        this.cryptoService = cryptoService;
        this.workerCount = workerCount;
        this.queueCapacity = queueCapacity;
        this.retentionSeconds = retentionSeconds;
        // A non-positive configuration must not authorise unlimited retries.
        this.maxAttempts = Math.max(1, maxAttempts);
        // A non-positive lease would fail every attempt mid-hash and a non-positive reaper cadence
        // is meaningless, so both are rejected loudly at startup instead of being clamped.
        if (executionLeaseSeconds <= 0) {
            throw new IllegalArgumentException(
                    "utiltools.jobs.execution-lease-seconds must be positive, was " + executionLeaseSeconds);
        }
        if (executionReaperIntervalMs <= 0) {
            throw new IllegalArgumentException("utiltools.jobs.execution-reaper-interval-ms must be positive, was "
                    + executionReaperIntervalMs);
        }
        // Zero is legal and means "no grace": the drain forces immediately, which is what a test
        // (and an operator who wants a hard stop) needs. Only a negative budget is rejected.
        if (shutdownGraceSeconds < 0) {
            throw new IllegalArgumentException(
                    "utiltools.jobs.shutdown-grace-seconds must not be negative, was " + shutdownGraceSeconds);
        }
        // A non-positive quota would refuse every submission, so it is rejected loudly at startup.
        if (maxActiveJobsPerClient <= 0) {
            throw new IllegalArgumentException(
                    "utiltools.jobs.max-active-jobs-per-client must be positive, was " + maxActiveJobsPerClient);
        }
        // 0 is legal and means "no server default"; anything outside 0..3600 would let a
        // configuration value admit a deadline the request validator rejects, so it fails loudly.
        if (defaultDeadlineSeconds < 0 || defaultDeadlineSeconds > MAX_DEADLINE_SECONDS) {
            throw new IllegalArgumentException(
                    "utiltools.jobs.default-deadline-seconds must be between 0 and 3600, was "
                            + defaultDeadlineSeconds);
        }
        // A non-positive cap would either evict every terminal row or disable retention entirely.
        if (maxRetainedJobs <= 0) {
            throw new IllegalArgumentException(
                    "utiltools.jobs.max-retained-jobs must be positive, was " + maxRetainedJobs);
        }
        this.executionLeaseSeconds = executionLeaseSeconds;
        this.executionReaperIntervalMs = executionReaperIntervalMs;
        this.defaultDeadlineSeconds = defaultDeadlineSeconds;
        this.shutdownGraceSeconds = shutdownGraceSeconds;
        this.maxActiveJobsPerClient = maxActiveJobsPerClient;
        this.maxRetainedJobs = maxRetainedJobs;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.executor = new ThreadPoolExecutor(workerCount, workerCount, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), new ThreadPoolExecutor.AbortPolicy());
        // Fair admission (REQ-01): one dispatcher owns every hand-off, so a submitting thread never
        // blocks on the pool and the round-robin order is decided in exactly one place. It is a daemon
        // and waits on dispatchLock, so an abandoned service costs no thread and no runnable work.
        this.dispatcher = new Thread(this::dispatchLoop, DISPATCHER_THREAD_NAME);
        this.dispatcher.setDaemon(true);
        this.dispatcher.start();
    }

    /**
     * Test/compatibility overload: an explicit drain grace with auditing disabled. The parameter
     * list is unchanged from before the retained-row cap and the configurable default deadline
     * existed, so the cap keeps its default and deadlines are disabled ({@code 0}). Unannotated on
     * purpose — Spring must keep selecting the {@code @Autowired} constructor above.
     */
    public BatchHashJobService(CryptoService cryptoService, int workerCount, int queueCapacity,
            long retentionSeconds, int maxAttempts, long executionLeaseSeconds,
            long executionReaperIntervalMs, long shutdownGraceSeconds, int maxActiveJobsPerClient,
            Clock clock) {
        this(cryptoService, workerCount, queueCapacity, retentionSeconds, maxAttempts,
                executionLeaseSeconds, executionReaperIntervalMs, shutdownGraceSeconds,
                maxActiveJobsPerClient, 0L, DEFAULT_MAX_RETAINED_JOBS, clock);
    }

    /**
     * Compatibility overload for callers (and tests) that predate the drain grace: the parameter
     * list is unchanged and the configured default window applies. Unannotated on purpose — Spring
     * must keep selecting the {@code @Autowired} constructor above.
     */
    public BatchHashJobService(CryptoService cryptoService, int workerCount, int queueCapacity,
            long retentionSeconds, int maxAttempts, long executionLeaseSeconds,
            long executionReaperIntervalMs, int maxActiveJobsPerClient, Clock clock) {
        this(cryptoService, workerCount, queueCapacity, retentionSeconds, maxAttempts,
                executionLeaseSeconds, executionReaperIntervalMs, DEFAULT_SHUTDOWN_GRACE_SECONDS,
                maxActiveJobsPerClient, clock);
    }

    /**
     * Test/compatibility overload: same as the {@code @Autowired} constructor, without the
     * per-client quota (the default applies).
     */
    public BatchHashJobService(CryptoService cryptoService, int workerCount, int queueCapacity,
            long retentionSeconds, int maxAttempts, long executionLeaseSeconds,
            long executionReaperIntervalMs, Clock clock) {
        this(cryptoService, workerCount, queueCapacity, retentionSeconds, maxAttempts,
                executionLeaseSeconds, executionReaperIntervalMs, DEFAULT_MAX_ACTIVE_JOBS_PER_CLIENT, clock);
    }

    /**
     * Test convenience: the same service with an explicit journal, so a unit test can assert the
     * per-attempt audit trail without a Spring context. Unannotated on purpose — Spring must keep
     * selecting the {@code @Autowired} constructor above.
     */
    public BatchHashJobService(CryptoService cryptoService, int workerCount, int queueCapacity,
            long retentionSeconds, int maxAttempts, long executionLeaseSeconds,
            long executionReaperIntervalMs, Clock clock, AuditJournalService journal) {
        this(cryptoService, workerCount, queueCapacity, retentionSeconds, maxAttempts,
                executionLeaseSeconds, executionReaperIntervalMs, DEFAULT_MAX_ACTIVE_JOBS_PER_CLIENT, clock);
        this.journal = journal;
    }

    /**
     * Test convenience: an explicit per-client quota and journal, so a unit test can assert quota
     * admission and the per-attempt audit trail without a Spring context.
     */
    public BatchHashJobService(CryptoService cryptoService, int workerCount, int queueCapacity,
            long retentionSeconds, int maxAttempts, long executionLeaseSeconds,
            long executionReaperIntervalMs, int maxActiveJobsPerClient, Clock clock,
            AuditJournalService journal) {
        this(cryptoService, workerCount, queueCapacity, retentionSeconds, maxAttempts,
                executionLeaseSeconds, executionReaperIntervalMs, maxActiveJobsPerClient, clock);
        this.journal = journal;
    }

    /**
     * Test convenience: an explicit drain grace so a test can exercise the forced phase without
     * sleeping. The journal stays last, so {@code null} still means "auditing disabled".
     */
    public BatchHashJobService(CryptoService cryptoService, int workerCount, int queueCapacity,
            long retentionSeconds, int maxAttempts, long executionLeaseSeconds,
            long executionReaperIntervalMs, long shutdownGraceSeconds, int maxActiveJobsPerClient,
            Clock clock, AuditJournalService journal) {
        this(cryptoService, workerCount, queueCapacity, retentionSeconds, maxAttempts,
                executionLeaseSeconds, executionReaperIntervalMs, shutdownGraceSeconds,
                maxActiveJobsPerClient, 0L, DEFAULT_MAX_RETAINED_JOBS, clock);
        this.journal = journal;
    }

    /**
     * Test convenience: an explicit retained-row cap so a test can force count pressure with a
     * handful of jobs. The journal stays last, so {@code null} still means "auditing disabled".
     */
    public BatchHashJobService(CryptoService cryptoService, int workerCount, int queueCapacity,
            long retentionSeconds, int maxAttempts, long executionLeaseSeconds,
            long executionReaperIntervalMs, long shutdownGraceSeconds, int maxActiveJobsPerClient,
            int maxRetainedJobs, Clock clock, AuditJournalService journal) {
        this(cryptoService, workerCount, queueCapacity, retentionSeconds, maxAttempts,
                executionLeaseSeconds, executionReaperIntervalMs, shutdownGraceSeconds,
                maxActiveJobsPerClient, 0L, maxRetainedJobs, clock);
        this.journal = journal;
    }

    /**
     * Test convenience: an explicit server default deadline and journal, so a test can exercise the
     * defaulted and disabled bound without a Spring context. Unannotated on purpose — Spring must keep
     * selecting the {@code @Autowired} constructor above.
     */
    public BatchHashJobService(CryptoService cryptoService, int workerCount, int queueCapacity,
            long retentionSeconds, int maxAttempts, long executionLeaseSeconds,
            long executionReaperIntervalMs, long shutdownGraceSeconds, int maxActiveJobsPerClient,
            long defaultDeadlineSeconds, Clock clock, AuditJournalService journal) {
        this(cryptoService, workerCount, queueCapacity, retentionSeconds, maxAttempts,
                executionLeaseSeconds, executionReaperIntervalMs, shutdownGraceSeconds,
                maxActiveJobsPerClient, defaultDeadlineSeconds, DEFAULT_MAX_RETAINED_JOBS, clock);
        this.journal = journal;
    }

    /**
     * Optional journal injection: auditing stays absent in test slices and in direct construction,
     * in which case attempts run unjournalled and {@link JobAttempt#auditEntryId()} stays null.
     *
     * @param journal the audit journal, or null to disable per-attempt journaling
     */
    @Autowired(required = false)
    public void setAuditJournal(AuditJournalService journal) {
        this.journal = journal;
    }

    /**
     * Registers the job, enqueues it, and returns its snapshot. Registration, {@code execute} and
     * the rejection rollback share one critical section, so "remove the job and its idempotency
     * mapping atomically" is literally true: no concurrent submitter can ever observe a job id
     * whose job was rolled back.
     */
    public BatchHashJobResponse submit(BatchHashRequest request) {
        return submit(request, ClientKeyResolver.UNKNOWN_CLIENT);
    }

    /**
     * Registers the job for {@code clientKey}, enqueues it, and returns its snapshot.
     *
     * <p>Registration and admission are one critical section: the per-client quota is reserved
     * (incremented) in the same {@code registryLock} section that publishes the job row and its
     * idempotency mapping, so no concurrent submitter can observe a job that holds no slot, and a
     * same-key/same-payload duplicate returns the existing snapshot without consuming a second slot.
     * {@link JobQuotaExceededException} is thrown before any registration or reservation, so a
     * refused submission leaves no job row, no idempotency mapping and no counter change.</p>
     *
     * @param request   the batch to hash
     * @param clientKey the identity to charge; {@code null}/blank is charged to
     *                  {@link ClientKeyResolver#UNKNOWN_CLIENT}
     * @throws IdempotencyConflictException when the key is reused with a different payload
     * @throws JobQuotaExceededException    when this client already holds the configured maximum
     *                                      non-terminal jobs
     * @throws JobQueueFullException        when the bounded executor rejects the submission
     */
    public BatchHashJobResponse submit(BatchHashRequest request, String clientKey) {
        String quotaKey = normalizeClientKey(clientKey);
        // Captured on the servlet thread: this is the id the accepted request will be audited under,
        // and it must survive the handoff to the pool.
        String requestId = RequestContext.get();
        String key = (request.idempotencyKey() == null || request.idempotencyKey().isBlank())
                ? null
                : request.idempotencyKey();
        // Resolved once, before the registry section: the bound is part of the job's identity, so it
        // must be the same value the fingerprint is computed from and the job is stamped with.
        long effectiveDeadlineSeconds = resolveEffectiveDeadline(request.deadlineSeconds());
        String fingerprint = fingerprint(request.algorithm(), request.items(), request.secretKey(),
                effectiveDeadlineSeconds);

        synchronized (registryLock) {
            if (key != null) {
                String existingJobId = idempotencyIndex.get(key);
                if (existingJobId != null) {
                    MutableJob existing = jobs.get(existingJobId);
                    if (existing != null) {
                        if (!existing.fingerprint.equals(fingerprint)) {
                            // An idempotency key identifies exactly one canonical payload: the
                            // conflicting submission is refused before any registration or enqueue,
                            // so it leaves no job row and no idempotency mapping behind.
                            throw new IdempotencyConflictException(key, existing.jobId);
                        }
                        return snapshot(existing); // duplicate submission: same jobId, no second execution
                    }
                    // The mapping is dangling: every removal of a job is paired with a conditional
                    // removal of its mapping, but a mapping can still outlive its row (a rollout, a
                    // legacy row). Drop it so this key starts exactly one new job instead of
                    // answering a 404-id snapshot forever.
                    idempotencyIndex.remove(key, existingJobId);
                }
            }

            // Count enforcement runs here, still holding registryLock and before the new row is
            // published (Plan §4.4 order: duplicate resolution, cap, quota, registration): the
            // registry is trimmed to the cap on every submission, including one that is about to be
            // refused, and the QUEUED row created below can never be selected as its own victim.
            enforceRetainedJobCapLocked();

            // Read-only: a refusal below leaves the job and idempotency registries untouched.
            int active = activeJobsByClient.getOrDefault(quotaKey, 0);
            if (active >= maxActiveJobsPerClient) {
                throw new JobQuotaExceededException(maxActiveJobsPerClient, active);
            }
            // REQ-02 order 4: the global admission gate. reservedCapacity counts every accepted
            // attempt that is queued or running, i.e. exactly the worker-count + queue-capacity bound
            // the executor used to enforce, and it is checked before anything is registered, so a
            // refused submission leaves no job row, no idempotency mapping, no attempt correlation and
            // no counter change behind.
            if (reservedCapacity >= workerCount + queueCapacity) {
                throw jobQueueFull();
            }

            MutableJob job = new MutableJob(UUID.randomUUID().toString(), key,
                    request.algorithm(), request.items(), request.secretKey(), fingerprint, Instant.now(clock),
                    quotaKey, maxAttempts, effectiveDeadlineSeconds);
            // Reserve before publishing: the increment and the registrations below share this
            // registryLock section, so the quota can neither be bypassed nor double-counted.
            activeJobsByClient.merge(quotaKey, 1, Integer::sum);
            reservedCapacity++; // one global capacity unit per accepted attempt (REQ-02)
            job.quotaReserved.set(true);
            if (key != null) {
                idempotencyIndex.put(key, job.jobId);
            }
            jobs.put(job.jobId, job);

            // Snapshot taken at registration, before enqueue, so the 202 body of a *first*
            // submission always reports the accepted state (QUEUED) even if a worker thread starts
            // and finishes immediately. A duplicate submission (fast path above) instead returns the
            // current live snapshot of the already-registered job, which may already be COMPLETED.
            BatchHashJobResponse accepted = snapshot(job);

            // Correlation is recorded before the handoff, so the worker can install the *submitter's*
            // id rather than whatever the reused pool thread happened to carry.
            job.attemptRequestIds.put(1, requestId);

            // REQ-01: the first attempt's generation is explicit rather than implicit, and it enters
            // this client's FIFO lane instead of being handed to the pool by the request thread.
            if (!enqueue(job, 1)) {
                // Rollback must undo BOTH registrations: the idempotency index stores job ids
                // (String), so the conditional remove must be given the id, not the job object.
                jobs.remove(job.jobId, job);
                if (key != null) {
                    idempotencyIndex.remove(key, job.jobId);
                }
                // A rejected submission never started, so it must leave no correlation behind either.
                job.attemptRequestIds.remove(1);
                // REQ-07: undo the reservation in the same section, so a rejected submission can
                // never leave a client looking busier than it is.
                job.quotaReserved.set(false);
                decrementQuotaLocked(quotaKey);
                // REQ-02: the global capacity unit is returned with the quota claim, exactly once,
                // and the lane never saw the attempt (enqueue refused it), so no orphan entry exists.
                releaseCapacityLocked();
                throw jobQueueFull();
            }

            return accepted;
        }
    }

    /**
     * The client's current non-terminal (QUEUED or RUNNING) job count. Package-private test
     * observability only: it is not exposed by any controller and has no production caller.
     */
    int activeJobsForClient(String clientKey) {
        String quotaKey = normalizeClientKey(clientKey);
        synchronized (registryLock) {
            return activeJobsByClient.getOrDefault(quotaKey, 0);
        }
    }

    /**
     * Accepted (queued or running) attempts currently holding a global capacity unit — the counter
     * behind the {@code worker-count + queue-capacity} gate. Package-private test observability only:
     * it lets the rollback tests prove a rejected submission returns the counter to its pre-submission
     * value (no leaked increment), and it has no production caller.
     */
    int reservedCapacityForTests() {
        synchronized (registryLock) {
            return reservedCapacity;
        }
    }

    private static String normalizeClientKey(String clientKey) {
        return (clientKey == null || clientKey.isBlank()) ? ClientKeyResolver.UNKNOWN_CLIENT : clientKey;
    }

    /**
     * The bound this submission is charged against: the per-request value when present, otherwise the
     * configured server default. {@code 0} means "no bound", which is exactly today's behaviour.
     *
     * <p>The per-request range is re-checked here rather than only on the record: bean validation
     * guards the HTTP route, but a direct caller (a test, an internal scheduler) never passes through
     * it, and a value of {@code 0} would otherwise short-circuit this method into "unbounded" instead
     * of failing. Rejecting it loudly keeps one meaning for {@code 0} - the configured default - and
     * never turns a malformed request into a silently immortal job.</p>
     *
     * @throws IllegalArgumentException when the request value is outside {@code 1..3600}
     */
    private long resolveEffectiveDeadline(Integer requestDeadlineSeconds) {
        if (requestDeadlineSeconds == null) {
            return defaultDeadlineSeconds;
        }
        long requested = requestDeadlineSeconds.longValue();
        if (requested < 1 || requested > MAX_DEADLINE_SECONDS) {
            throw new IllegalArgumentException(
                    "deadlineSeconds must be between 1 and 3600, was " + requested);
        }
        return requested;
    }

    /** Decrements (and removes at zero) a client's counter entry. Caller must hold {@code registryLock}. */
    private void decrementQuotaLocked(String clientKey) {
        Integer current = activeJobsByClient.get(clientKey);
        if (current == null || current <= 0) {
            return; // defensively a no-op: a missing entry must never underflow into over-admission
        }
        if (current == 1) {
            activeJobsByClient.remove(clientKey); // zero entries are never retained (REQ-01)
            return;
        }
        activeJobsByClient.put(clientKey, current - 1);
    }

    /**
     * Releases one reserved slot. Always called <em>after</em> the job-monitor block that claimed the
     * reservation, so {@code registryLock} is never acquired while a job monitor is held
     * (registryLock -&gt; job monitor is the only order this class allows).
     */
    private void releaseQuota(String clientKey) {
        synchronized (registryLock) {
            decrementQuotaLocked(clientKey);
            releaseCapacityLocked();
        }
    }

    /**
     * Returns one global capacity unit. Always paired with a quota release in the same
     * {@code registryLock} section, and always guarded by the caller's claim-once
     * {@code quotaReserved} CAS, so a slot can never be returned twice (REQ-02).
     */
    private void releaseCapacityLocked() {
        if (reservedCapacity > 0) {
            reservedCapacity--;
        }
    }

    /**
     * The queue-full refusal shared by every admission path, so the message an operator reads is
     * identical whichever bound refused the submission (REQ-02, REQ-04).
     */
    private JobQueueFullException jobQueueFull() {
        return new JobQueueFullException("Hash job queue is full (worker-count=" + workerCount
                + ", queue-capacity=" + queueCapacity + "); retry after capacity frees");
    }

    /**
     * Appends one attempt to its client's FIFO lane and wakes the dispatcher, i.e. the replacement for
     * the direct {@code executor.execute(...)} hand-off (REQ-01).
     *
     * <p>The global capacity unit for the attempt has already been claimed by the caller under
     * {@code registryLock}, so this method only decides <em>placement</em>; the single reason it can
     * refuse is a drain that has already closed the lanes, which on both call paths produces exactly
     * the same rollback as the old executor rejection (REQ-02).</p>
     *
     * @return {@code true} when the attempt is queued, {@code false} when a drain refused it
     */
    private boolean enqueue(MutableJob job, int generation) {
        synchronized (dispatchLock) {
            if (shutdownRequested) {
                return false;
            }
            Deque<LaneEntry> lane = lanes.get(job.clientKey);
            if (lane == null) {
                lane = new ArrayDeque<>();
                lanes.put(job.clientKey, lane);
                // A client joins the ring at the tail. If it was already served in the live round its
                // membership in servedThisRound is kept, so a re-enqueue (retry, duplicate) cannot buy
                // it a second dispatch before the other clients with queued work have had their turn
                // (REQ-01, REQ-03).
                laneOrder.addLast(job.clientKey);
            } else if (!laneOrder.contains(job.clientKey)) {
                // Defensive: the ring entry is kept while the lane is non-empty, so this cannot fire.
                laneOrder.addLast(job.clientKey);
            }
            lane.addLast(new LaneEntry(job, generation));
            dispatchLock.notifyAll();
            return true;
        }
    }

    /**
     * Removes every pending entry of one attempt from its lane. Called by the terminal writers that
     * withdraw a <em>queued</em> job (cancel, queue-wait deadline expiry) and by the drain, so a
     * dispatched entry is never a stale generation (REQ-02, REQ-03).
     *
     * <p>The capacity and quota units are released by the caller's claim-once
     * {@code quotaReserved} CAS, never here, so a purge can never double-release a slot.</p>
     */
    private void purgeLaneEntries(MutableJob job, int generation) {
        synchronized (dispatchLock) {
            Deque<LaneEntry> lane = lanes.get(job.clientKey);
            if (lane == null) {
                return;
            }
            lane.removeIf(entry -> entry.job() == job && entry.generation() == generation);
            if (lane.isEmpty()) {
                forgetLaneLocked(job.clientKey);
            }
        }
    }

    /**
     * Drops a client's lane once nothing is pending for it: the lane and its ring slot, so the queue
     * bookkeeping is bounded by "clients with queued work", never "clients ever seen". Its
     * {@code servedThisRound} membership, if any, is dropped when the round closes (Plan §5). Caller
     * must hold {@code dispatchLock}.
     */
    private void forgetLaneLocked(String clientKey) {
        lanes.remove(clientKey);
        laneOrder.remove(clientKey);
    }

    /** Drops every pending entry; used by the drain, whose sweep then settles the affected rows. */
    private void clearLanes() {
        synchronized (dispatchLock) {
            shutdownRequested = true;
            lanes.clear();
            laneOrder.clear();
            servedThisRound.clear();
            dispatchedInFlight = 0;
            dispatchLock.notifyAll();
        }
    }

    /**
     * The client's current pending lane depth. Package-private test/ops observability only, mirroring
     * {@link #activeJobsForClient}: it is not exposed by any controller and has no production caller,
     * so {@code BatchHashJobResponse} keeps every one of its fields (REQ-04).
     */
    int queuedJobsForClient(String clientKey) {
        String quotaKey = normalizeClientKey(clientKey);
        synchronized (dispatchLock) {
            Deque<LaneEntry> lane = lanes.get(quotaKey);
            return lane == null ? 0 : lane.size();
        }
    }

    /**
     * The fair-admission dispatcher: the only thread that ever hands work to the pool (REQ-01).
     *
     * <p>Selection runs under {@link #dispatchLock} and the hand-off runs outside it, so a full
     * executor queue can never wedge the dispatcher, and the dispatcher never takes
     * {@code registryLock} or a job monitor while holding the lock (Plan §4.6).</p>
     */
    private void dispatchLoop() {
        while (true) {
            List<LaneEntry> handOff = new ArrayList<>();
            synchronized (dispatchLock) {
                while (!shutdownRequested) {
                    if (dispatchedInFlight >= workerCount) {
                        break; // every worker is busy: the lanes, not the pool queue, hold the backlog
                    }
                    LaneEntry entry = pollNextLocked();
                    if (entry == null) {
                        break;
                    }
                    dispatchedInFlight++;
                    handOff.add(entry);
                }
                if (handOff.isEmpty()) {
                    if (shutdownRequested) {
                        return;
                    }
                    if (dispatchedInFlight == 0 && laneOrder.isEmpty()) {
                        // Nothing queued and nothing running: forget the round bookkeeping, so an
                        // idle service costs nothing and no per-client state outlives the round.
                        servedThisRound.clear();
                    }
                    try {
                        dispatchLock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    continue;
                }
                if (shutdownRequested) {
                    return; // the drain takes over every row it still finds non-terminal
                }
            }
            for (LaneEntry entry : handOff) {
                handOff(entry);
            }
        }
    }

    /**
     * Hands one admitted entry to the bounded executor. The capacity gate keeps
     * {@code reservedCapacity <= worker-count + queue-capacity}, so {@link
     * ThreadPoolExecutor.AbortPolicy} can only fire here if that invariant is broken; the backstop
     * then puts the entry back at the tail of its own lane instead of losing the attempt - but only
     * while that attempt is still dispatchable, see {@link #reEnqueue}.
     */
    private void handOff(LaneEntry entry) {
        try {
            executor.execute(() -> {
                try {
                    executeAttempt(entry.job().jobId, entry.generation());
                } finally {
                    attemptFinished(); // the worker slot is free again; the dispatcher refills it
                }
            });
        } catch (RejectedExecutionException e) {
            // Unreachable by the capacity invariant; yield before retrying rather than spin, so a
            // broken invariant degrades to a slow drain instead of a hot loop or a lost job.
            attemptFinished();
            synchronized (dispatchLock) {
                if (!shutdownRequested) {
                    try {
                        dispatchLock.wait(1L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            log.warn("batch-hash dispatch: hand-off rejected by the pool; re-queueing the entry", e);
            reEnqueue(entry);
        }
    }

    /**
     * Marks one handed-off attempt finished and wakes the dispatcher, so the freed worker slot is
     * refilled from the fair lanes by the dispatcher (never by the worker thread itself). Guarded by
     * {@code dispatchLock} so the in-flight counter can never undershoot after a drain.
     */
    private void attemptFinished() {
        synchronized (dispatchLock) {
            if (dispatchedInFlight > 0) {
                dispatchedInFlight--;
            }
            dispatchLock.notifyAll();
        }
    }

    /**
     * Re-appends a rejected hand-off at the tail of its own client's lane, provided the attempt is
     * still queued and still the live generation.
     *
     * <p>The backstop runs after {@link #attemptFinished()}, so a terminal writer (cancel, queue-wait
     * deadline expiry, drain sweep) can have withdrawn the attempt in between. Those writers already
     * returned its one capacity unit through the claim-once {@code quotaReserved} CAS, so re-appending
     * would only put a no-op entry back into the lane and transiently inflate
     * {@link #queuedJobsForClient}; such an attempt is therefore dropped instead. The status is read
     * <em>before</em> {@code dispatchLock} is taken, because {@code job monitor -> dispatchLock} is the
     * only lock order this class allows.</p>
     */
    private void reEnqueue(LaneEntry entry) {
        MutableJob job = entry.job();
        boolean dispatchable;
        synchronized (job) {
            dispatchable = job.status == JobStatus.QUEUED && job.attempt == entry.generation();
        }
        if (!dispatchable) {
            log.debug("batch-hash dispatch: dropped a rejected hand-off for an attempt that is no longer queued (job={})",
                    job.jobId);
            return; // terminal or superseded: its slot was released once by the terminal writer
        }
        synchronized (dispatchLock) {
            if (shutdownRequested) {
                return;
            }
            Deque<LaneEntry> lane = lanes.get(entry.job().clientKey);
            if (lane == null) {
                lane = new ArrayDeque<>();
                lanes.put(entry.job().clientKey, lane);
                laneOrder.addLast(entry.job().clientKey);
            }
            lane.addLast(entry);
            dispatchLock.notifyAll();
        }
    }

    /**
     * Selects and removes the next entry to dispatch, implementing the round barrier that bounds
     * starvation (REQ-01, Plan §4.2): a client is eligible only while its key is absent from
     * {@code servedThisRound}; when no client is eligible the round is closed (the set is pruned),
     * which makes every client with a pending lane eligible again. A client therefore receives at
     * most one dispatch per round while any other client still has queued work, so the wait is
     * bounded by the number of clients with queued work - not by the depth of any lane and not by how
     * much service a client accumulated in earlier rounds.
     *
     * <p>Closing the round is the <em>only</em> reset, and it fires on both "no eligible client"
     * exits: the saturated ring (every pending client was served) and the empty ring (nobody has work
     * queued). The empty-ring close is what makes the retained set bounded by live work instead of by
     * dispatch count: without it a long job keeping a worker busy would let the ring drain between
     * submissions, and every such dispatch would leave one client key behind until the service went
     * fully idle. The residual case is a ring that never empties - one client keeps a lane non-empty
     * while new clients stream in - where the round stays open and marks accumulate for clients whose
     * lanes already drained; so the set is additionally capped at {@code worker-count +
     * queue-capacity} (one key per unit of live queued work) by closing the round when it is reached.
     * Memory is therefore O(worker-count + queue-capacity) in all cases, never a function of the
     * client population or of dispatch count, and a cap-triggered close can only restart the round
     * from the ring head, so the starvation bound below still holds (REQ-01, Plan §5 Memory).</p>
     */
    private LaneEntry pollNextLocked() {
        if (servedThisRound.size() >= dispatchCapacityLocked()) {
            // Hard memory cap: a ring that never empties cannot grow the set past one key per unit of
            // global worker+queue capacity. Closing here restarts the round at the ring head, so the
            // longest-waiting client is still the first one served.
            closeRoundLocked();
        }
        String chosen = selectEligibleLocked();
        if (chosen == null) {
            // The round barrier resets in both cases: everybody pending has been served (saturated
            // ring), or nothing is queued at all (empty ring), where the close is vacuously fair
            // because no client has work that these marks could starve.
            closeRoundLocked();
            if (laneOrder.isEmpty()) {
                return null; // nothing queued at all: the caller blocks until a lane refills
            }
            chosen = selectEligibleLocked();
            if (chosen == null) {
                return null; // unreachable: a fresh round makes every pending client eligible
            }
        }
        Deque<LaneEntry> lane = lanes.get(chosen);
        LaneEntry entry = lane.pollFirst();
        servedThisRound.add(chosen);
        laneOrder.remove(chosen);
        if (lane.isEmpty()) {
            // Lane and ring slot go at once; the served mark survives until this round closes, so a
            // re-enqueue (retry, duplicate) from this client cannot buy it a second dispatch before the
            // clients that were already waiting have had their turn (REQ-01, REQ-03).
            forgetLaneLocked(chosen);
        } else {
            laneOrder.addLast(chosen); // the ring cursor advances to the next client
        }
        return entry;
    }

    /**
     * Closes the current round: every client with a pending lane has been served (or nothing is
     * queued), so the served marks are dropped and the epoch ends with no residue
     * ({@code servedThisRound} never outlives the round it describes). This is the only reset, so no
     * counter can drift or wrap, and the set is bounded by the clients with queued work in the live
     * round rather than by dispatch count or the client population.
     */
    private void closeRoundLocked() {
        servedThisRound.clear();
    }

    /**
     * Upper bound on the number of served marks a round may retain: one per unit of live global
     * capacity ({@code worker-count + queue-capacity}), because that is the most attempts that can be
     * queued or running at any instant. Caller must hold {@link #dispatchLock}.
     */
    private int dispatchCapacityLocked() {
        return workerCount + queueCapacity;
    }

    /**
     * The first client from the head of {@code laneOrder} that still has pending work and has not been
     * served in the current round, or {@code null} when the round is complete.
     */
    private String selectEligibleLocked() {
        for (String client : laneOrder) {
            Deque<LaneEntry> lane = lanes.get(client);
            if (lane == null || lane.isEmpty()) {
                continue; // defensive: a drained lane is pruned by the writers that drain it
            }
            if (!servedThisRound.contains(client)) {
                return client;
            }
        }
        return null;
    }

    /** One queued attempt: the job reference plus the generation that fences a superseded attempt. */
    private record LaneEntry(MutableJob job, int generation) {
    }

    /** Current snapshot of a job; throws {@link JobNotFoundException} for unknown or evicted ids. */
    public BatchHashJobResponse getJob(String jobId) {
        return snapshot(requireJob(jobId));
    }

    /**
     * Lists a bounded, newest-first window of the jobs currently in the registry.
     *
     * <p>The registry is iterated without {@code registryLock} (a weakly consistent view), so a job
     * submitted or evicted while the list is built is simply present or absent. Listing is strictly
     * read-only: no job field is written, the idempotency index is untouched and no clock is
     * advanced. The status filter is evaluated under the job monitor, so a half-written transition
     * is never observed. Every element is built by {@link #snapshot(MutableJob)}, i.e. exactly the
     * {@code GET /{jobId}} projection, so no {@code secretKey} or raw item payload can leak.</p>
     *
     * @param status optional status filter; {@code null} lists every status
     * @param limit  requested window size, clamped to {@code [1, 100]} (never rejected)
     * @return matching snapshots, newest first ({@code createdAt} descending, {@code jobId} ascending);
     *         an empty list when the registry is empty or nothing matches
     */
    public List<BatchHashJobResponse> listJobs(JobStatus status, int limit) {
        int bounded = Math.max(1, Math.min(MAX_LIST_LIMIT, limit));
        PriorityQueue<MutableJob> kept = new PriorityQueue<>(bounded, LIST_ORDER_INVERSE);
        for (MutableJob job : jobs.values()) { // weakly consistent view, no registryLock
            if (status != null) {
                synchronized (job) {
                    if (job.status != status) {
                        continue; // filtered under the job monitor
                    }
                }
            }
            kept.offer(job);
            if (kept.size() > bounded) {
                kept.poll(); // drops the entry that ranks last under LIST_ORDER
            }
        }
        List<MutableJob> selected = new ArrayList<>(kept);
        selected.sort(LIST_ORDER);
        List<BatchHashJobResponse> listed = new ArrayList<>(selected.size());
        for (MutableJob job : selected) {
            listed.add(snapshot(job)); // the single projection, point-in-time per element
        }
        return List.copyOf(listed);
    }

    /**
     * Requeues a FAILED job for one more execution attempt.
     *
     * <p>Validation and the {@code FAILED -> QUEUED} transition happen in a single monitor section,
     * so a worker can never complete the job between the status check and the requeue. The attempt
     * counter is incremented in that same section and doubles as the new generation: a worker from
     * the previous attempt fails its generation check and writes nothing. The jobId and the
     * idempotency mapping are untouched by a retry.</p>
     *
     * <p>If the executor rejects the requeued attempt, the previous FAILED snapshot is restored -
     * including the attempt counter - and the existing {@link JobQueueFullException} is thrown, so
     * the attempt is not consumed and the retry can be repeated once capacity frees.</p>
     *
     * @throws JobNotFoundException        when the id is unknown or was already evicted
     * @throws JobRetryConflictException   when the job is not FAILED, or its attempt budget is spent
     * @throws JobQuotaExceededException   when the job's client already holds the configured maximum
     *                                     non-terminal jobs (the FAILED job is left untouched)
     * @throws JobQueueFullException       when the bounded executor rejects the requeued attempt
     */
    public BatchHashJobResponse retry(String jobId) {
        // The retry is its own audited request: the new attempt is correlated to *this* caller's id,
        // never to the id that created (or first retried) the job.
        String requestId = RequestContext.get();
        MutableJob job = requireJob(jobId); // unknown/evicted -> JobNotFoundException (404 JOB_NOT_FOUND)
        // A retry that cannot succeed must not touch the client's counter at all: the reservation
        // below is visible to concurrent submissions by the same client, so an ineligible retry
        // checked only *after* it would briefly expose a spurious 429 window. This monitor section
        // is the cheap fail-fast; the authoritative checks repeat after the reservation because the
        // job can change state in between.
        synchronized (job) {
            if (jobs.get(job.jobId) != job) {
                throw new JobNotFoundException(job.jobId);
            }
            if (job.status != JobStatus.FAILED || job.attempt >= job.maxAttempts) {
                throw new JobRetryConflictException(job.jobId, job.status, job.attempt, job.maxAttempts);
            }
        }
        // REQ-06: the reservation belongs to the job's client, not to the caller, so a retry cannot
        // escape to another bucket. It is taken under registryLock *before* the monitor below, so an
        // exhausted quota refuses the retry before a single field of the FAILED snapshot is touched.
        reserveQuota(job);
        boolean keepReservation = false;
        try {
            synchronized (job) {
                // Registry membership is re-checked under the monitor: an eviction pass that acquired
                // the monitor first has already unregistered this instance, and a 202 for a job the
                // caller can no longer poll (or that a same-key submit would duplicate) must not happen.
                if (jobs.get(job.jobId) != job) {
                    throw new JobNotFoundException(job.jobId);
                }
                if (job.status != JobStatus.FAILED || job.attempt >= job.maxAttempts) {
                    // Thrown before a single field is touched: the 409 leaves the snapshot identical.
                    throw new JobRetryConflictException(job.jobId, job.status, job.attempt, job.maxAttempts);
                }
                FailedState before = FailedState.of(job);
                int admittedAttempt = ++job.attempt;
                job.status = JobStatus.QUEUED;
                job.startedAt = null;
                job.completedAt = null;
                job.error = null;
                // A retried attempt continues from the durable checkpoint prefix: it resumes at the
                // next uncommitted index and the prefix is preserved here (a rejected retry therefore
                // has nothing to undo in it). Only the published results are cleared.
                job.results = null;
                job.lastHeartbeatAt = null; // the new attempt stamps its own lease at admission
                job.executionLeaseExpiresAt = null;
                // A fresh window measured from the retry, not from createdAt: a creation-based bound
                // would re-expire the instant the requeued attempt was admitted. Zero stays unbounded.
                job.deadlineAt = job.deadlineSeconds > 0
                        ? Instant.now(clock).plusSeconds(job.deadlineSeconds)
                        : null;
                job.attemptRequestIds.put(admittedAttempt, requestId);
                // REQ-03: the requeued attempt re-enters *this* client's lane at its tail, so it cannot
                // jump ahead of work other clients already have waiting.
                if (!enqueue(job, admittedAttempt)) {
                    before.restore(job); // attempt decremented, status FAILED, error/completedAt back
                    job.attemptRequestIds.remove(admittedAttempt); // the attempt never started
                    throw jobQueueFull();
                }
                keepReservation = true; // only an accepted requeue keeps the slot
                return snapshot(job); // QUEUED, attempt = 2, attempt-1 entry retained
            }
        } finally {
            if (!keepReservation) {
                // A refused (409/404/429) retry must restore the client's count: the claim is
                // dropped and the decrement runs here, outside every job monitor (REQ-08).
                job.quotaReserved.set(false);
                releaseQuota(job.clientKey);
            }
        }
    }

    /**
     * Reserves one slot for {@code job}'s client. Called only under {@code registryLock}; throws
     * {@link JobQuotaExceededException} (touching nothing) when the client is at its ceiling.
     */
    private void reserveQuota(MutableJob job) {
        synchronized (registryLock) {
            if (jobs.get(job.jobId) != job) {
                throw new JobNotFoundException(job.jobId);
            }
            // I-02: a job holds at most one live reservation. A concurrent retry that already
            // reserved this job is about to requeue it, so this caller is answered with the same
            // conflict it would observe after that requeue instead of stealing the live claim.
            if (!job.quotaReserved.compareAndSet(false, true)) {
                JobStatus observed;
                int observedAttempt;
                synchronized (job) { // registryLock -> job monitor is the only legal order
                    observed = job.status;
                    observedAttempt = job.attempt;
                }
                throw new JobRetryConflictException(job.jobId, observed, observedAttempt, job.maxAttempts);
            }
            int active = activeJobsByClient.getOrDefault(job.clientKey, 0);
            if (active >= maxActiveJobsPerClient) {
                job.quotaReserved.set(false); // nothing was reserved, so give the claim back
                throw new JobQuotaExceededException(maxActiveJobsPerClient, active);
            }
            // REQ-02: a retried attempt takes a global capacity unit under the same gate as a fresh
            // submission, so the worker-count + queue-capacity bound is unchanged by fair admission.
            if (reservedCapacity >= workerCount + queueCapacity) {
                job.quotaReserved.set(false); // nothing was reserved, so give the claim back
                throw jobQueueFull();
            }
            activeJobsByClient.merge(job.clientKey, 1, Integer::sum);
            reservedCapacity++;
        }
    }

    /**
     * Requests cancellation of a job: {@code QUEUED -> CANCELLED} withdraws it outright, while
     * {@code RUNNING -> CANCELLING} only records the request and lets the worker honour it at its next
     * checkpoint.
     *
     * <p>Both transitions happen under the job's own monitor, so they cannot interleave with the
     * worker's admission check: either the worker has not started (and will see CANCELLED and return
     * without hashing), or it is already RUNNING and turns CANCELLING — the writer that observes
     * CANCELLING is the one that terminalizes CANCELLED exactly once ({@code INV-CAN-2}). A
     * cancellation request deliberately releases nothing: the per-client quota slot and the execution
     * lease stay with the in-flight worker, because the job is still non-terminal and still hashing.</p>
     *
     * <p>Calling this again on a CANCELLING or CANCELLED job returns the current snapshot and writes
     * nothing ({@code INV-CAN-3}); COMPLETED and FAILED remain a conflict and leave the snapshot
     * untouched.</p>
     *
     * @throws JobNotFoundException              when the id is unknown or was already evicted
     * @throws JobCancellationConflictException  when the job is COMPLETED or FAILED
     */
    public BatchHashJobResponse cancel(String jobId) {
        MutableJob job = requireJob(jobId);
        boolean released = false;
        BatchHashJobResponse cancelled;
        synchronized (job) {
            if (job.status == JobStatus.CANCELLING || job.status == JobStatus.CANCELLED) {
                return snapshot(job); // idempotent: nothing is written
            }
            if (job.status == JobStatus.RUNNING) {
                // Records the request only: the worker (or the reaper / drain, if it stalls) still has
                // to write the terminal CANCELLED, so the reservation and the lease stay untouched.
                job.status = JobStatus.CANCELLING;
                return snapshot(job);
            }
            if (job.status != JobStatus.QUEUED) {
                throw new JobCancellationConflictException(job.jobId, job.status);
            }
            job.status = JobStatus.CANCELLED;
            job.completedAt = Instant.now(clock);
            job.results = null; // explicit: nothing was hashed
            job.checkpoint.clear(); // a withdrawn job retains no partial digests
            job.failedItems.clear(); // nor any per-item failure: a withdrawal takes the whole batch
            job.itemErrors.clear();
            // The per-attempt projections go with them, so a withdrawn job's history row reports no
            // partial outcome either (the snapshot and the attempt rows must agree).
            job.attemptSucceededItems.remove(job.attempt);
            job.attemptFailedItems.remove(job.attempt);
            job.error = null;
            job.lastHeartbeatAt = null;
            job.executionLeaseExpiresAt = null;
            // REQ-02/REQ-03: the pending lane entry is withdrawn in the same monitor section as the
            // terminal write, so a queued job that was just cancelled can never be dispatched (the
            // worker's status fence would reject it anyway), and its capacity unit is returned once
            // by the claim-once CAS below rather than by the purge.
            purgeLaneEntries(job, job.attempt);
            // Same monitor section as the terminal write: exactly one caller can claim the
            // reservation, so a repeated cancel (or a later eviction) cannot release it again.
            released = job.quotaReserved.compareAndSet(true, false);
            cancelled = snapshot(job);
        }
        if (released) {
            releaseQuota(job.clientKey);
            // Bounded retention on the terminal write itself: a fast submit/complete churn would
            // otherwise let terminal rows pile up between two scheduled cleanup passes.
            enforceRetainedJobCap();
        }
        return cancelled;
    }

    /**
     * Writes the single {@code CANCELLING -> CANCELLED} transition. The caller must already hold the
     * job monitor and must have re-checked that this generation still owns a CANCELLING job, so the
     * terminal write, the attempt upsert, the audit close and the reservation claim can never be split
     * by a concurrent writer ({@code INV-CAN-2}).
     *
     * <p>Clearing the checkpoint on the way out is what makes cancellation a withdrawal rather than a
     * failure: a retry resumes an empty prefix, exactly as {@code QUEUED -> CANCELLED} does, and no
     * partial digest survives in memory.</p>
     *
     * @return {@code true} when this call claimed the quota reservation; the caller releases the slot
     *         after leaving the monitor section
     */
    private boolean finalizeCancelledLocked(MutableJob job, int generation) {
        job.status = JobStatus.CANCELLED;
        job.completedAt = Instant.now(clock);
        job.results = null; // explicit: a cancelled batch never publishes results
        job.checkpoint.clear(); // a cancelled job retains no partial digests
        job.failedItems.clear(); // nor any per-item failure: a withdrawal takes the whole batch
        job.itemErrors.clear();
        // Dropped before recordAttempt below, so the CANCELLED row reports no partial outcome.
        job.attemptSucceededItems.remove(generation);
        job.attemptFailedItems.remove(generation);
        job.error = null;
        job.lastHeartbeatAt = null;
        job.executionLeaseExpiresAt = null;
        recordAttempt(job, generation, JobStatus.CANCELLED, job.startedAt, job.completedAt, null);
        // Closes the entry the attempt opened and drops its id, so the worker's own outcome and every
        // recovery path find nothing left to close and the row is journalled exactly once.
        succeedQuietly(job, generation, "CANCELLED");
        return job.quotaReserved.compareAndSet(true, false);
    }

    /**
     * Terminalizes a CANCELLING job outside the monitor, releasing the quota slot after the section.
     *
     * @return {@code true} when this call wrote the terminal status, {@code false} when another writer
     *         had already terminalized the attempt
     */
    private boolean finalizeCancelled(MutableJob job, int generation) {
        boolean released;
        synchronized (job) {
            if (job.status != JobStatus.CANCELLING) {
                return false; // a concurrent reaper or drain already settled this attempt
            }
            released = finalizeCancelledLocked(job, generation);
        }
        if (released) {
            releaseQuota(job.clientKey); // after the monitor block (REQ-08)
            enforceRetainedJobCap(); // outside the monitor: registryLock -> job monitor, never reverse
        }
        return true;
    }

    /**
     * True when {@code job} carries a total-life bound that has been reached at {@code now}. The
     * boundary matches the lease comparison ({@code !expiry.isAfter(now)}): a job is out of time
     * <em>at</em> its {@code deadlineAt}, not only after it. Callers pass the clock instant.
     */
    private static boolean deadlinePassed(MutableJob job, Instant now) {
        return job.deadlineAt != null && !job.deadlineAt.isAfter(now);
    }

    /**
     * True when {@code job} holds an execution lease that expired at or before {@code now}. Extracted
     * so the lease path keeps byte-identical semantics for jobs without a deadline.
     */
    private static boolean leaseLapsed(MutableJob job, Instant now) {
        return job.executionLeaseExpiresAt != null && !job.executionLeaseExpiresAt.isAfter(now);
    }

    /**
     * Writes the single non-terminal -> {@code FAILED} / {@link #JOB_DEADLINE_EXCEEDED} transition.
     * The caller must already hold the job monitor, so the terminal write, the attempt upsert, the
     * audit close and the reservation claim can never be split by a concurrent writer.
     *
     * <p>Deadline failure is not cancellation: the committed checkpoint prefix is deliberately left
     * alone (a retry resumes at that index) while {@code results} stays null, so a job that ran out of
     * time never publishes a partial digest. The lease is cleared because the attempt is over.</p>
     *
     * @return {@code true} when this call claimed the quota reservation; the caller releases the slot
     *         after leaving the monitor section
     */
    private boolean failDeadlineLocked(MutableJob job, int generation, Instant now) {
        // A QUEUED generation was never admitted, so no worker is carrying its request id any more:
        // drop the mapping here so a job that expired in the queue retains no correlation ids of
        // generations that can never run (a RUNNING generation is cleaned by its own worker exit).
        if (job.status == JobStatus.QUEUED) {
            job.attemptRequestIds.remove(generation);
            // REQ-02/REQ-03: a queued attempt whose deadline passed is withdrawn from its lane in the
            // same monitor section that makes it terminal, so it can never be dispatched afterwards;
            // the capacity unit goes back with the claim-once CAS at the end of this method.
            purgeLaneEntries(job, generation);
        }
        job.status = JobStatus.FAILED;
        job.error = JOB_DEADLINE_EXCEEDED;
        job.completedAt = now;
        job.results = null; // no partial digest is ever published for a job that ran out of time
        job.executionLeaseExpiresAt = null;
        job.lastHeartbeatAt = null;
        recordAttempt(job, generation, JobStatus.FAILED, job.startedAt, now, JOB_DEADLINE_EXCEEDED);
        // Closes the entry this attempt opened (a no-op for a QUEUED job that never admitted) and drops
        // its id, so the worker's own late outcome finds nothing left to close: one journal row.
        failQuietly(job, generation, JOB_DEADLINE_EXCEEDED);
        return job.quotaReserved.compareAndSet(true, false);
    }

    /**
     * Terminalizes a RUNNING job whose deadline passed outside the monitor, releasing the quota slot
     * after the section. Mirrors {@link #finalizeCancelled}.
     *
     * @return {@code true} when this call wrote the terminal status, {@code false} when the attempt was
     *         already settled (or is CANCELLING, whose withdrawal keeps priority)
     */
    private boolean finalizeDeadline(MutableJob job, int generation) {
        boolean released;
        synchronized (job) {
            if (job.attempt != generation || job.status != JobStatus.RUNNING) {
                return false; // cancelled, retried or reaped while the item was being hashed
            }
            released = failDeadlineLocked(job, generation, Instant.now(clock));
        }
        if (released) {
            releaseQuota(job.clientKey); // after the monitor block (REQ-08)
        }
        return true;
    }

    /**
     * Renews the execution lease of the attempt that is currently RUNNING.
     *
     * <p>A worker calls this to say "still working": the check and both lease writes happen inside a
     * single monitor section, so the only attempt a heartbeat can extend is the one that owns the
     * job at that instant ({@code INV-HB-1}). The new expiry is derived from
     * {@link Instant#now(Clock)}, never from the stored expiry, and an expired lease is refused
     * instead of revived ({@code INV-HB-2}), so a heartbeat arriving after
     * {@link #reapExpiredExecutions(Instant)} claimed the attempt cannot resurrect it. Nothing but
     * {@code lastHeartbeatAt} and {@code executionLeaseExpiresAt} is written ({@code INV-HB-3}).</p>
     *
     * <p>The {@code HEARTBEAT} journal row is opened and closed within this call and its id is never
     * stored in {@code attemptAuditEntryIds}, so the attempt's own entry keeps closing with the
     * attempt's outcome ({@code INV-HB-4}).</p>
     *
     * @param jobId the job whose lease should be renewed
     * @return the post-renewal snapshot
     * @throws JobNotFoundException      when the id is unknown or was already evicted
     * @throws JobRetryConflictException when the job exists but is not RUNNING
     * @throws IllegalArgumentException  when the job is RUNNING but holds no live lease
     */
    public BatchHashJobResponse heartbeat(String jobId) {
        MutableJob job = requireJob(jobId); // order 1: unknown/evicted -> 404 JOB_NOT_FOUND
        synchronized (job) {
            if (job.status != JobStatus.RUNNING) { // order 2: QUEUED/COMPLETED/FAILED/CANCELLED -> 409
                throw new JobRetryConflictException(job.jobId, job.status, job.attempt, job.maxAttempts,
                        "renew its execution lease");
            }
            Instant now = Instant.now(clock);
            Instant current = job.executionLeaseExpiresAt;
            if (current == null || !current.isAfter(now)) { // order 3: dead lease -> 400
                throw new IllegalArgumentException("Execution lease for job " + jobId
                        + " has expired; the attempt is reaped and can be retried from attempt "
                        + (job.attempt + 1));
            }
            job.lastHeartbeatAt = now;
            job.executionLeaseExpiresAt = now.plusSeconds(executionLeaseSeconds);
            recordHeartbeatQuietly(job, job.attempt, job.executionLeaseExpiresAt);
        }
        return snapshot(job);
    }

    @Scheduled(fixedDelayString = "${utiltools.jobs.cleanup-interval-ms:60000}")
    public void evictExpiredJobs() {
        evictExpiredJobs(Instant.now(clock).minusSeconds(retentionSeconds));
    }

    /**
     * Removes terminal jobs (COMPLETED, FAILED, CANCELLED) whose completion instant is older than
     * {@code cutoff}, together with their idempotency entries. QUEUED and RUNNING jobs are never
     * evicted. Public and clock-free so tests can advance the cutoff instead of waiting.
     *
     * <p>The whole pass holds {@code registryLock}, so a removal and the idempotency-index update
     * that mirrors it are one atomic step for {@link #submit}. The scheduled pass therefore enforces
     * time-based retention and then count-based retention ({@link #enforceRetainedJobCapLocked()}) in
     * the same section.</p>
     *
     * @return the total number of rows this pass removed: the time-based evictions plus the terminal
     *         rows removed because the retained-row cap was exceeded (Plan §4.5)
     */
    public int evictExpiredJobs(Instant cutoff) {
        int evicted = 0;
        synchronized (registryLock) {
            for (MutableJob job : jobs.values()) {
                synchronized (job) {
                    // Terminality and removal share one monitor section with retry's FAILED -> QUEUED
                    // transition, so a retry can neither slip between the check and the remove nor
                    // target a job this pass has unregistered: either the retry wins (the job is
                    // QUEUED here and is not evicted) or this pass wins (retry re-checks the registry
                    // under the same monitor and answers 404).
                    boolean evictable = isTerminal(job.status)
                            && job.completedAt != null
                            && job.completedAt.isBefore(cutoff);
                    if (evictable && jobs.remove(job.jobId, job)) {
                        evicted++;
                        if (job.idempotencyKey != null) {
                            idempotencyIndex.remove(job.idempotencyKey, job.jobId);
                        }
                        // No quota release here on purpose: every terminal status above was written
                        // together with the reservation claim, so a terminal job holds no slot.
                        // Releasing on eviction would under-count and over-admit this client later.
                    }
                }
            }
            // Count-based retention rides the same pass, under the same lock: an idle registry still
            // converges on the cap even if no terminal transition happens to run enforcement.
            int enforced = enforceRetainedJobCapLocked();
            return evicted + enforced;
        }
    }

    /**
     * Enforces the retained-row cap for a caller that holds no monitor yet: a terminal transition
     * calls this after it has left every job monitor, so the {@code registryLock} it takes here can
     * never be acquired in the reverse order.
     */
    private void enforceRetainedJobCap() {
        synchronized (registryLock) {
            enforceRetainedJobCapLocked();
        }
    }

    /**
     * Enforces the retained-row cap; the caller must already hold {@code registryLock}.
     *
     * <p>At most {@code maxRetainedJobs} terminal rows survive: {@code QUEUED}, {@code RUNNING} and
     * {@code CANCELLING} rows are never candidates, so a registry full of live work stays above the
     * cap instead of losing a job a client is still polling.</p>
     *
     * @return the number of terminal rows evicted by this pass
     */
    private int enforceRetainedJobCapLocked() {
        if (jobs.size() <= maxRetainedJobs) {
            return 0; // fast path: fewer rows than the cap cannot violate it, so no scan at all
        }
        List<MutableJob> candidates = new ArrayList<>();
        for (MutableJob job : jobs.values()) {
            synchronized (job) { // registryLock -> job monitor is the only legal order
                if (isTerminal(job.status) && job.completedAt != null) {
                    candidates.add(job);
                }
            }
        }
        int excess = candidates.size() - maxRetainedJobs;
        if (excess <= 0) {
            return 0; // the common case: non-terminal rows are what push the registry over the cap
        }
        candidates.sort(EVICTION_ORDER); // oldest completedAt first, jobId ascending on ties
        int evicted = 0;
        // Every candidate is walked, not just the first `excess` slots: a victim skipped below (a
        // concurrent retry flipped it back to QUEUED, or another writer already removed it) is
        // replaced by the next-oldest candidate, so one skip cannot leave the registry above the cap
        // until the following pass.
        for (MutableJob victim : candidates) {
            if (evicted >= excess) {
                break;
            }
            synchronized (victim) {
                // Re-checked under the monitor: a victim is only removed while it is still terminal
                // and still the row this pass selected, so a concurrent retry cannot be unmapped.
                if (!isTerminal(victim.status) || !jobs.remove(victim.jobId, victim)) {
                    continue;
                }
                if (victim.idempotencyKey != null) {
                    // Conditional on purpose: a same-key resubmission may already have repointed the
                    // index at a newer job, and eviction must never drop that newer mapping.
                    idempotencyIndex.remove(victim.idempotencyKey, victim.jobId);
                }
                // No quota release, no surviving-job mutation: a terminal row holds no slot and the
                // snapshots, attempt histories, checkpoints and audit entries of other rows stay put.
            }
            evicted++;
        }
        if (evicted > 0) {
            log.debug("batch-hash retention: evicted={} terminal jobs beyond the cap of {}", evicted,
                    maxRetainedJobs);
        }
        return evicted;
    }

    private static boolean isTerminal(JobStatus status) {
        return status == JobStatus.COMPLETED || status == JobStatus.FAILED || status == JobStatus.CANCELLED;
    }

    @Scheduled(fixedDelayString = "${utiltools.jobs.execution-reaper-interval-ms:5000}")
    public void reapExpiredExecutions() {
        reapExpiredExecutions(Instant.now(clock));
    }

    /**
     * Reaps every non-terminal attempt whose execution lease lapsed at or before {@code now} and every
     * non-terminal attempt whose total-life deadline passed at or before {@code now}, exactly once per
     * attempt. This is the backstop for work no worker ever reaches: a QUEUED job that was never
     * admitted fails on its deadline even though it holds no lease, and a RUNNING job fails on its
     * deadline <em>even while its execution lease is still live</em>, so a worker that keeps renewing
     * cannot hash a large batch indefinitely.
     *
     * <p>Precedence is fixed: a CANCELLING job is terminalized {@code CANCELLED} (the operator's
     * withdrawal beats both the deadline and the lease), then a RUNNING job is failed on its deadline
     * before its lease is considered, then a QUEUED job is failed on its deadline. Every branch leaves
     * the status terminal (or the job untouched), so a second pass over the same {@code now} reaps
     * nothing and writes no attempt row, no journal row and no quota release. The reaped attempt keeps
     * its attempt number, so the worker that stalled inside {@code hash} fails its status fence when it
     * finally returns and cannot overwrite this snapshot; {@link #retry(String)} then starts
     * {@code attempt + 1}.
     *
     * <p>The pass is clock-free, holds one job monitor at a time and never removes a job, so monitor
     * hold time stays a function of the scalar writes below and the pass cannot deadlock with the
     * worker or with {@link #evictExpiredJobs(Instant)}.</p>
     *
     * @param now the instant lease expiry is compared against
     * @return the number of attempts reaped by this pass
     */
    public int reapExpiredExecutions(Instant now) {
        int reaped = 0;
        for (MutableJob job : jobs.values()) {
            boolean released = false;
            synchronized (job) {
                if (job.status == JobStatus.CANCELLING) {
                    if (!deadlinePassed(job, now) && !leaseLapsed(job, now)) {
                        continue; // neither bound reached: leave the pending withdrawal to its worker
                    }
                    // A pending cancellation whose worker stalled (or whose bound passed): honour the
                    // request rather than overwriting it with FAILED, so the outcome stays CANCELLED.
                    released = finalizeCancelledLocked(job, job.attempt);
                    reaped++;
                } else if (job.status == JobStatus.RUNNING) {
                    if (deadlinePassed(job, now)) {
                        // Total-execution bound reached: this wins over a still-live lease, because a
                        // worker that renews forever would otherwise hash the batch indefinitely.
                        released = failDeadlineLocked(job, job.attempt, now);
                        reaped++;
                    } else if (leaseLapsed(job, now)) {
                        job.status = JobStatus.FAILED;
                        job.error = EXECUTION_LEASE_EXPIRED;
                        job.completedAt = now;
                        job.results = null;
                        job.executionLeaseExpiresAt = null;
                        job.lastHeartbeatAt = null;
                        recordAttempt(job, job.attempt, JobStatus.FAILED, job.startedAt, now,
                                EXECUTION_LEASE_EXPIRED);
                        // Close the audit entry the stalled worker opened: the fence keeps that worker
                        // from writing its own (late) outcome, so this is the only terminal write for
                        // the attempt.
                        failQuietly(job, job.attempt, EXECUTION_LEASE_EXPIRED);
                        // Claimed in the same section as the terminal write; a late worker fenced by
                        // this reap never reaches its own claim, so the slot is released exactly once.
                        released = job.quotaReserved.compareAndSet(true, false);
                        reaped++;
                    }
                } else if (job.status == JobStatus.QUEUED) {
                    if (!deadlinePassed(job, now)) {
                        continue; // still waiting inside its bound
                    }
                    // Queue-wait bound reached on a job no worker ever admitted: it opened no audit
                    // entry (failQuietly is then a no-op), yet the attempt row is recorded so a retry
                    // is attempt 2 resuming from index 0.
                    released = failDeadlineLocked(job, job.attempt, now);
                    reaped++;
                } else {
                    continue; // terminal jobs are already settled
                }
            }
            if (released) {
                releaseQuota(job.clientKey); // after the monitor block (REQ-08)
            }
            // REQ-02, enforcement site #4 (Plan §4.3): this pass just published a terminal row (reaped
            // FAILED, or a finalised CANCELLED) — the two `continue` branches above are the only way
            // to skip this line. It runs after the monitor block, so the order stays
            // registryLock -> job monitor, and it is not gated on `released`: a quota claim already
            // spent by another writer must not leave the terminal row in place until the next pass.
            enforceRetainedJobCap();
        }
        return reaped;
    }

    /**
     * Terminalizes the service: in-flight work gets a bounded grace window, everything still
     * non-terminal afterwards is failed with {@link #JOB_SHUTDOWN}.
     *
     * <p>Two phases. Phase one first ends the fair-admission dispatcher
     * ({@link #stopDispatcher()}): the drain flag is set and every pending lane entry is dropped under
     * {@link #dispatchLock}, so from that moment no attempt can be dispatched and {@link #submit} and
     * {@link #retry} are refused by the lane gate in {@link #enqueue}, rolling back through the same
     * queue-full path a saturated admission uses ({@link JobQueueFullException}) - not through
     * {@link RejectedExecutionException}, which remains only the pool's unreachable backstop. Because
     * the lanes are already empty, the {@link ThreadPoolExecutor#shutdown()} that follows admits no new
     * work: it only lets the attempts that were <em>already</em> handed off - at most
     * {@code worker-count} of them - finish inside the configured grace window. Jobs that were still
     * queued in a lane are consequently never executed; the sweep below settles them
     * {@link #JOB_SHUTDOWN}. Phase two interrupts whatever is still running
     * ({@link ThreadPoolExecutor#shutdownNow()}) and sweeps the registry. The sweep fails only
     * {@code QUEUED}/{@code RUNNING} jobs, keeps their attempt number and their checkpoint, and closes
     * each open audit entry exactly once, so a worker that ignores interruption and returns later
     * writes nothing: the existing status/attempt fence rejects it.</p>
     *
     * <p>Idempotent by construction rather than by flag: a second call re-runs no-op
     * {@code shutdown()}/{@code shutdownNow()} and finds no non-terminal job, so it adds no attempt,
     * no audit row and no quota release.</p>
     */
    @PreDestroy
    public void shutdown() {
        synchronized (shutdownMonitor) {
            // The dispatcher is stopped first (REQ-01/REQ-02): after this call no new attempt can be
            // handed off, every pending lane is dropped, and submit/retry are refused through the
            // existing queue-full rollback, so the sweep below settles exactly the rows still present.
            stopDispatcher();
            // Nothing can be admitted any more (the lanes are gone), so this only waits for the
            // attempts already handed off; jobs that were queued in a lane are settled by the sweep.
            executor.shutdown();
            boolean terminated = awaitTerminationBounded(shutdownGraceSeconds);
            List<Runnable> discarded = executor.shutdownNow(); // phase 2: interrupt what is still running
            int failed = failNonTerminalJobs(JOB_SHUTDOWN);
            log.info("batch-hash drain: terminated={}, forced={}, failed={}", terminated, discarded.size(),
                    failed);
        }
    }

    /**
     * Ends the dispatcher and empties every lane.
     *
     * <p>Both are done under {@link #dispatchLock} so the dispatcher either sees the flag before its
     * next selection or is interrupted out of its wait; the caller then joins it (bounded) before the
     * pool is touched, so no hand-off can race the drain. Any attempt that was already handed off keeps
     * its capacity unit and is settled by the normal worker/reaper/drain path.</p>
     */
    private void stopDispatcher() {
        clearLanes(); // sets shutdownRequested and drops every pending entry
        dispatcher.interrupt();
        try {
            dispatcher.join(TimeUnit.SECONDS.toMillis(5L));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // never swallow the drain caller's interrupt
        }
    }

    /**
     * Waits at most {@code graceSeconds} for the pool to terminate.
     *
     * @return {@code true} when every task finished, {@code false} on timeout or interruption
     */
    private boolean awaitTerminationBounded(long graceSeconds) {
        if (graceSeconds <= 0) {
            return executor.isTerminated();
        }
        try {
            return executor.awaitTermination(graceSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            // Never swallow the caller's interrupt: the drain proceeds to the forced phase.
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Fails every non-terminal job with {@code error}, mirroring {@link #reapExpiredExecutions(Instant)}
     * line for line: one job monitor at a time, terminal write + attempt upsert + audit close +
     * reservation claim in that section, quota release outside it.
     *
     * @return the number of jobs failed by this pass
     */
    private int failNonTerminalJobs(String error) {
        int failed = 0;
        for (MutableJob job : jobs.values()) {
            boolean released = false;
            synchronized (job) {
                if (job.status == JobStatus.CANCELLING) {
                    // The caller already asked for CANCELLED: a drain settles the request instead of
                    // turning it into a JOB_SHUTDOWN failure the caller never asked for.
                    released = finalizeCancelledLocked(job, job.attempt);
                    failed++;
                } else if (job.status == JobStatus.QUEUED || job.status == JobStatus.RUNNING) {
                    Instant now = Instant.now(clock);
                    if (job.status == JobStatus.QUEUED) {
                        // Defensive: the dispatcher was stopped (and its lanes dropped) before this
                        // sweep, so this is a no-op unless a hand-off was still in flight.
                        purgeLaneEntries(job, job.attempt);
                    }
                    job.status = JobStatus.FAILED; // the fence's status half now fails for a late worker
                    job.error = error;
                    job.completedAt = now;
                    job.results = null; // unpublished results are never published by a drain
                    job.executionLeaseExpiresAt = null;
                    job.lastHeartbeatAt = null;
                    // The checkpoint prefix is deliberately kept: it is the evidence a retry resumes from.
                    recordAttempt(job, job.attempt, JobStatus.FAILED, job.startedAt, now, error);
                    // Closes the entry this attempt opened and drops its id, so a late worker's own
                    // close finds nothing and a second drain cannot close the row again.
                    failQuietly(job, job.attempt, error);
                    // Claimed in the same section as the terminal write: the slot is released exactly once.
                    released = job.quotaReserved.compareAndSet(true, false);
                    failed++;
                } else {
                    continue; // terminal jobs (including one that completed inside the grace) untouched
                }
            }
            if (released) {
                releaseQuota(job.clientKey); // after the monitor block (REQ-08)
            }
            // REQ-02, enforcement site #6 (Plan §4.3): the drain sweep publishes FAILED (JOB_SHUTDOWN)
            // rows in bulk, and the `continue` above is the only path that settles no row. Enforced
            // after the monitor block and after releaseQuota, so the order stays
            // registryLock -> job monitor and a drain sweep cannot push the registry past the cap.
            enforceRetainedJobCap();
        }
        return failed;
    }

    private MutableJob requireJob(String jobId) {
        MutableJob job = jobs.get(jobId);
        if (job == null) {
            throw new JobNotFoundException(jobId);
        }
        return job;
    }

    private BatchHashJobResponse snapshot(MutableJob job) {
        synchronized (job) {
            // Lease state is a property of RUNNING alone: admission always stamps both instants, and
            // every non-RUNNING status projects null regardless of any leftover field.
            boolean running = job.status == JobStatus.RUNNING;
            // The checkpoint projection is derived, never stored: a second counter could drift across
            // the reap / retry / cancel edges.
            int completedItems = switch (job.status) {
                case COMPLETED -> job.items.size(); // the prefix was cleared when results were published
                case CANCELLING, CANCELLED -> 0; // never expose a cancelled job's prefix
                default -> job.checkpoint.size(); // QUEUED / RUNNING / FAILED
            };
            // Per-item projections, derived the same way (REQ-02). A cancelled job withdrew its
            // progress, so it discloses neither set; a COMPLETED job's checkpoint was moved into
            // results, so its successes are exactly 0..size-1.
            List<Integer> succeededItems = switch (job.status) {
                case COMPLETED -> allIndexes(job.items.size());
                case CANCELLING, CANCELLED -> List.of();
                default -> List.copyOf(job.checkpoint.keySet()); // TreeMap key set is ascending
            };
            List<Integer> failedItems = switch (job.status) {
                case COMPLETED, CANCELLING, CANCELLED -> List.of();
                default -> ascending(job.failedItems);
            };
            return new BatchHashJobResponse(job.jobId, job.idempotencyKey, job.status, job.items.size(),
                    job.results, job.error, job.createdAt, job.startedAt, job.completedAt,
                    job.attempt, job.maxAttempts, List.copyOf(job.attemptHistory),
                    running ? job.executionLeaseExpiresAt : null,
                    running ? job.lastHeartbeatAt : null,
                    completedItems,
                    job.deadlineAt, // projected for every status: null when the job has no bound
                    succeededItems, failedItems);
        }
    }

    /**
     * Worker entry point. Package-private like {@link #evictExpiredJobs(Instant)} so tests can replay
     * a superseded generation. An evicted job resolves to {@code null} and is simply not executed,
     * rather than throwing inside the pool.
     */
    void executeAttempt(String jobId, int generation) {
        MutableJob job = jobs.get(jobId);
        if (job != null) {
            executeJob(job, generation);
        }
    }

    private void executeJob(MutableJob job, int generation) {
        try {
            boolean deadlineExpired = false;
            boolean admissionReleased = false;
            synchronized (job) {
                if (job.status != JobStatus.QUEUED || job.attempt != generation) {
                    return; // fenced: a superseded generation (or a cancelled/retried job) writes nothing
                }
                Instant now = Instant.now(clock);
                if (deadlinePassed(job, now)) {
                    // Queue-wait expiry: the bound passed before a worker admitted the job. It fails
                    // here exactly as the reaper would fail it - no audit entry is opened and no item
                    // is hashed, so completedItems stays 0 - and the quota is released below.
                    deadlineExpired = true;
                    admissionReleased = failDeadlineLocked(job, generation, now);
                } else {
                    job.status = JobStatus.RUNNING;
                    job.startedAt = now;
                    job.lastHeartbeatAt = now;
                    job.executionLeaseExpiresAt = now.plusSeconds(executionLeaseSeconds);
                    // The attempt continues at the first index neither hashed nor failed; recorded
                    // before the audit descriptor is built so both attempt history and journal agree
                    // where it started.
                    int resumedFrom = nextUnprocessedIndex(job);
                    job.attemptResumedFrom.put(generation, resumedFrom);
                    // Admission is the moment the attempt "starts": install the submitter's
                    // correlation id on this (possibly reused) pool thread and open exactly one audit
                    // entry here.
                    RequestContext.set(job.attemptRequestIds.get(generation));
                    String entryId = beginQuietly(job, generation);
                    if (entryId != null) {
                        job.attemptAuditEntryIds.put(generation, entryId);
                    }
                    recordAttempt(job, generation, JobStatus.RUNNING, job.startedAt, null, null);
                }
            }
            if (deadlineExpired) {
                if (admissionReleased) {
                    releaseQuota(job.clientKey); // after the monitor block (REQ-08)
                }
                return;
            }
            try {
                int resumedFrom = job.attemptResumedFrom.getOrDefault(generation, 0);
                for (int i = resumedFrom; i < job.items.size(); i++) {
                    if (isProcessed(job, i)) {
                        continue; // INV-PI-3: a succeeded or failed index is never hashed twice
                    }
                    if (!renewLease(job, generation)) {
                        return; // fenced: the attempt was reaped, retried or cancelled while hashing
                    }
                    HashResponse hashed;
                    try {
                        hashed = cryptoService.hash(
                                new HashRequest(job.algorithm, job.items.get(i), job.secretKey));
                    } catch (RuntimeException itemFailure) {
                        // INV-PI-1: one item's failure never aborts the batch. The index is recorded as
                        // failed - keeping every item already hashed - and the loop carries on.
                        if (!recordItemFailure(job, generation, i, itemFailure)) {
                            return; // fenced, or cancelled by the writer above: stop hashing
                        }
                        continue;
                    }
                    if (!commitCheckpoint(job, generation, i, hashed)) {
                        return; // fenced mid-hash: nothing is written, not even the item just hashed
                    }
                }
                boolean released;
                synchronized (job) {
                    if (job.attempt != generation) {
                        // The generation half covers an attempt superseded by retry.
                        return; // fenced: the attempt was requeued while hashing
                    }
                    if (job.status == JobStatus.CANCELLING) {
                        // The caller cancelled while the last item was hashing: its CANCELLED wins over
                        // this COMPLETED, so the results just computed are discarded with the prefix.
                        released = finalizeCancelledLocked(job, generation);
                    } else {
                        if (job.status != JobStatus.RUNNING) {
                            // Load-bearing: a reaped attempt keeps its attempt number but is FAILED, so
                            // the status half of this fence is what stops a late COMPLETED write.
                            return; // fenced: the attempt was reaped while hashing
                        }
                        Instant now = Instant.now(clock);
                        if (deadlinePassed(job, now)) {
                            // The bound wins over a last-item completion: the committed prefix stays as
                            // the retry resume point and results stay null, so no partial digest is
                            // published for a job that ran out of time.
                            released = failDeadlineLocked(job, generation, now);
                        } else {
                            if (isFullyHashed(job)) {
                                job.results = List.copyOf(job.checkpoint.values()); // ascending = input order
                                job.checkpoint.clear(); // results own the values; retained state stays bounded
                                job.error = null;
                                job.status = JobStatus.COMPLETED;
                                job.completedAt = Instant.now(clock);
                                job.lastHeartbeatAt = null;
                                job.executionLeaseExpiresAt = null;
                                recordAttempt(job, generation, JobStatus.COMPLETED, job.startedAt,
                                        job.completedAt, null);
                                // Same section that wrote the terminal status: the audit outcome can never
                                // disagree with the snapshot, and a journal fault cannot change it.
                                succeedQuietly(job, generation, "COMPLETED");
                                // Claimed in the same section as the terminal write: the slot covering this
                                // attempt is released exactly once, by the writer that terminated it.
                                released = job.quotaReserved.compareAndSet(true, false);
                            } else {
                                // INV-PI-6: at least one item failed. results stay null (a partial list is
                                // never published) and both per-item sets are retained, so the batch is
                                // resumable rather than lost.
                                job.results = null;
                                String detail = firstItemFailure(job);
                                job.error = detail == null
                                        ? BATCH_HASH_FAILED
                                        : BATCH_ITEM_HASH_FAILED + ": " + detail;
                                job.status = JobStatus.FAILED;
                                job.completedAt = Instant.now(clock);
                                job.lastHeartbeatAt = null;
                                job.executionLeaseExpiresAt = null;
                                recordAttempt(job, generation, JobStatus.FAILED, job.startedAt,
                                        job.completedAt, job.error);
                                failQuietly(job, generation, job.error,
                                        itemOutcomePayload(job, generation, "FAILED"));
                                released = job.quotaReserved.compareAndSet(true, false);
                            }
                        }
                    }
                }
                if (released) {
                    releaseQuota(job.clientKey); // after the monitor block (REQ-08)
                    enforceRetainedJobCap(); // outside the monitor: registryLock -> job monitor
                }
            } catch (Throwable t) {
                // Intentional: an Error must not wedge the pool, later jobs still run.
                boolean released = false;
                synchronized (job) {
                    if (job.attempt != generation) {
                        // A stale failure must not overwrite an attempt superseded by retry.
                        return; // fenced: the attempt was requeued while hashing
                    }
                    if (job.status == JobStatus.CANCELLING) {
                        // Cancellation beats a concurrent fault: the caller asked to stop, so the job
                        // ends CANCELLED with no error rather than FAILED.
                        released = finalizeCancelledLocked(job, generation);
                    } else if (job.status == JobStatus.RUNNING) {
                        job.error = describe(t);
                        job.status = JobStatus.FAILED;
                        job.completedAt = Instant.now(clock);
                        job.lastHeartbeatAt = null;
                        job.executionLeaseExpiresAt = null;
                        recordAttempt(job, generation, JobStatus.FAILED, job.startedAt, job.completedAt,
                                job.error);
                        failQuietly(job, generation, job.error);
                        released = job.quotaReserved.compareAndSet(true, false);
                    } else {
                        // Load-bearing, same reasoning as the COMPLETED fence above: a stale failure
                        // must not overwrite a reaped attempt.
                        return; // fenced: a superseded failure must not overwrite the reaped attempt
                    }
                }
                if (released) {
                    releaseQuota(job.clientKey); // after the monitor block (REQ-08)
                    enforceRetainedJobCap(); // outside the monitor: registryLock -> job monitor
                }
            }
        } finally {
            // Runs on the pool thread: the next job admitted on it must never inherit this id.
            RequestContext.clear();
        }
    }

    /**
     * Renews the execution lease of {@code generation} before the next item is hashed.
     *
     * <p>Under the job monitor it re-checks {@code job.attempt == generation && status == RUNNING},
     * so an attempt that was reaped, retried or cancelled while hashing is never re-armed: the lease
     * cannot be revived, and the worker stops hashing instead of burning CPU for a job it can no
     * longer complete.</p>
     *
     * <p>This is the cancellation checkpoint: a CANCELLING job is terminalized here, between two items,
     * so the batch stops within one item of the {@code DELETE} and the worker never resumes a lease for
     * a job the caller withdrew ({@code INV-CAN-2}).</p>
     *
     * @return {@code true} when the lease was renewed, {@code false} when the attempt was fenced
     */
    private boolean renewLease(MutableJob job, int generation) {
        boolean cancelPending = false;
        boolean deadlinePending = false;
        synchronized (job) {
            if (job.attempt != generation) {
                return false;
            }
            if (job.status == JobStatus.CANCELLING) {
                cancelPending = true; // terminalized below, outside this monitor section
            } else if (job.status != JobStatus.RUNNING) {
                return false;
            } else if (deadlinePassed(job, Instant.now(clock))) {
                // The total-life bound wins over a renewal: the lease is not re-armed, so the attempt
                // stops hashing at the index it had committed and the prefix is kept for a retry.
                deadlinePending = true; // terminalized below, outside this monitor section
            } else {
                job.lastHeartbeatAt = Instant.now(clock);
                job.executionLeaseExpiresAt = job.lastHeartbeatAt.plusSeconds(executionLeaseSeconds);
                return true;
            }
        }
        if (cancelPending) {
            finalizeCancelled(job, generation);
        } else if (deadlinePending) {
            finalizeDeadline(job, generation);
        }
        return false;
    }

    /**
     * Commits one hashed item to the job's checkpoint prefix.
     *
     * <p>The fence is deliberately re-checked <em>after</em> the hash: {@code CryptoService.hash}
     * can outlive the attempt (reaped, retried or superseded while the worker was inside it), and a
     * pre-hash-only fence would let a stale worker splice item {@code index} into a fresh
     * generation's checkpoint. The {@code containsKey / failedItems} guard additionally guarantees an
     * index is recorded exactly once: the loop walks indices ascending, so success is never overwritten
     * and a failed index is never converted into a success by a concurrent writer.</p>
     *
     * <p>A CANCELLING job is terminalized instead of appended to: the item just hashed is dropped with
     * the rest of the prefix, so a cancelled job never reports progress it did not publish
     * ({@code INV-CAN-2}).</p>
     *
     * @return {@code true} when the item was committed, {@code false} when this generation no longer
     *         owns the job and nothing at all was written
     */
    private boolean commitCheckpoint(MutableJob job, int generation, int index, HashResponse hashed) {
        boolean cancelPending = false;
        synchronized (job) {
            if (job.attempt != generation) {
                return false; // fenced, cancelled, reaped or superseded
            }
            if (job.status == JobStatus.CANCELLING) {
                cancelPending = true; // terminalized below, outside this monitor section
            } else if (job.status != JobStatus.RUNNING) {
                return false; // fenced, reaped or superseded
            } else if (job.checkpoint.containsKey(index) || job.failedItems.get(index)) {
                return true; // already recorded (INV-PI-2): a success is never overwritten
            } else {
                job.checkpoint.put(index, hashed);
                attemptSucceeded(job, generation).set(index);
                return true;
            }
        }
        if (cancelPending) {
            finalizeCancelled(job, generation);
        }
        return false;
    }

    /**
     * Records one item-level hash failure and lets the batch carry on.
     *
     * <p>The fence mirrors {@link #commitCheckpoint}: the generation and status are re-checked after
     * the throw, so a failure that surfaced after the attempt was reaped, retried or cancelled writes
     * nothing. Only a still-RUNNING generation of this attempt records the index.</p>
     *
     * @return {@code true} when the failure was recorded and the loop may continue; {@code false} when
     *         this generation no longer owns the job, or when the CANCELLING writer above terminalized
     *         it and the worker must stop
     */
    private boolean recordItemFailure(MutableJob job, int generation, int index,
            RuntimeException failure) {
        boolean cancelPending = false;
        synchronized (job) {
            if (job.attempt != generation) {
                return false; // fenced, cancelled, reaped or superseded
            }
            if (job.status == JobStatus.CANCELLING) {
                cancelPending = true; // terminalized below, outside this monitor section
            } else if (job.status != JobStatus.RUNNING) {
                return false; // fenced, reaped or superseded
            } else {
                job.failedItems.set(index);
                job.itemErrors.put(index, describeItemFailure(failure));
                attemptFailed(job, generation).set(index);
                log.debug("BATCH_HASH item {} of job {} attempt {} failed: {}", index, job.jobId,
                        generation, failure.toString());
                return true;
            }
        }
        if (cancelPending) {
            finalizeCancelled(job, generation);
        }
        return false;
    }

    /** Per-attempt succeeded index set of {@code generation}; created on first use. */
    private static BitSet attemptSucceeded(MutableJob job, int generation) {
        return job.attemptSucceededItems.computeIfAbsent(generation, key -> new BitSet());
    }

    /** Per-attempt failed index set of {@code generation}; created on first use. */
    private static BitSet attemptFailed(MutableJob job, int generation) {
        return job.attemptFailedItems.computeIfAbsent(generation, key -> new BitSet());
    }

    /** Ascending index list of a bit set: the compact, JSON-friendly projection of an index set. */
    private static List<Integer> ascending(BitSet indexes) {
        List<Integer> out = new ArrayList<>();
        for (int i = indexes.nextSetBit(0); i >= 0; i = indexes.nextSetBit(i + 1)) {
            out.add(i);
        }
        return out;
    }

    /**
     * {@code [0, itemCount)}: the per-item success projection of a COMPLETED job, whose checkpoint
     * has already been moved into {@code results}.
     */
    private static List<Integer> allIndexes(int itemCount) {
        List<Integer> out = new ArrayList<>(itemCount);
        for (int i = 0; i < itemCount; i++) {
            out.add(i);
        }
        return out;
    }

    /** True when {@code index} is already recorded (hashed or failed) and must not be re-hashed. */
    private static boolean isProcessed(MutableJob job, int index) {
        synchronized (job) {
            return job.checkpoint.containsKey(index) || job.failedItems.get(index);
        }
    }

    /**
     * The first index neither hashed nor failed: the checkpoint resume point of the next attempt.
     * Every index below it is already recorded, so it is also the size of the durable prefix a retry
     * keeps.
     */
    private static int nextUnprocessedIndex(MutableJob job) {
        synchronized (job) {
            int index = 0;
            while (index < job.items.size()
                    && (job.checkpoint.containsKey(index) || job.failedItems.get(index))) {
                index++;
            }
            return index;
        }
    }

    /** True when every item of the batch is hashed and none is recorded as failed. */
    private static boolean isFullyHashed(MutableJob job) {
        return job.failedItems.isEmpty() && job.checkpoint.size() == job.items.size();
    }

    /**
     * Bounded, content-free description of an item-level hash failure: the throwable's simple name and
     * message only, so neither the item text nor the secret key can reach a snapshot or the journal.
     */
    private static String describeItemFailure(Throwable failure) {
        String message = failure.getMessage();
        String text = failure.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
        return boundedAuditText(text, BATCH_ITEM_HASH_FAILED);
    }

    /**
     * The detail of the lowest-index failed item, or null when nothing failed; it is what a partially
     * hashed batch appends to its error code so the cause stays visible on the snapshot.
     */
    private static String firstItemFailure(MutableJob job) {
        int first = job.failedItems.nextSetBit(0);
        return first < 0 ? null : job.itemErrors.get(first);
    }

    /**
     * The per-attempt item-outcome payload of the attempt's closing journal entry ({@code REQ-03}):
     * which indices this attempt hashed, which it failed and the bounded cause of each failure.
     * Indices only - no item text and no secret key - so the journal stays a bounded replay trail.
     */
    private Map<String, Object> itemOutcomePayload(MutableJob job, int generation, String outcome) {
        List<Map<String, Object>> failures = new ArrayList<>();
        for (int i = job.failedItems.nextSetBit(0);
                i >= 0 && failures.size() < MAX_ITEM_ERROR_ENTRIES;
                i = job.failedItems.nextSetBit(i + 1)) {
            failures.add(Map.of("index", i, "error",
                    boundedAuditText(job.itemErrors.get(i), BATCH_ITEM_HASH_FAILED)));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("outcome", outcome);
        payload.put("resultCount", job.items.size());
        payload.put("resumedFrom", job.attemptResumedFrom.getOrDefault(generation, 0));
        payload.put("completedItems", completedItems(job));
        payload.put("succeededItems", ascending(attemptSucceeded(job, generation)));
        payload.put("failedItems", ascending(job.failedItems));
        payload.put("failedItemErrors", failures);
        return payload;
    }

    /**
     * Upserts the single history entry for {@code attempt}: it first appears as RUNNING and is then
     * replaced in place by its terminal status, so the history stays bounded by {@code maxAttempts}
     * and no entry is ever duplicated.
     */
    private void recordAttempt(MutableJob job, int attempt, JobStatus status, Instant startedAt,
            Instant completedAt, String error) {
        JobAttempt entry = new JobAttempt(attempt, status, startedAt, completedAt, error,
                job.attemptAuditEntryIds.get(attempt), job.attemptResumedFrom.getOrDefault(attempt, 0),
                ascending(attemptSucceeded(job, attempt)), ascending(attemptFailed(job, attempt)));
        for (int i = 0; i < job.attemptHistory.size(); i++) {
            if (job.attemptHistory.get(i).attempt() == attempt) {
                job.attemptHistory.set(i, entry);
                return;
            }
        }
        job.attemptHistory.add(entry);
    }

    /**
     * Opens the {@code BATCH_HASH} audit entry for {@code generation} at worker admission.
     *
     * <p>Best effort by contract: an audit failure must never change whether an attempt starts, so
     * every throwable is swallowed and a null id simply means "this attempt has no journal entry".
     * The descriptor is bounded and carries no payload: {@code items} and {@code secretKey} never
     * leave the job.</p>
     */
    private String beginQuietly(MutableJob job, int generation) {
        AuditJournalService audit = this.journal;
        if (audit == null) {
            return null;
        }
        try {
            return audit.begin(OperationType.BATCH_HASH, BatchHashAuditDescriptor.of(job, generation),
                    RequestContext.get()).id();
        } catch (RuntimeException e) {
            log.debug("BATCH_HASH audit begin failed for job {} attempt {}: {}", job.jobId, generation,
                    e.toString());
            return null;
        }
    }

    /** Closes the attempt's entry as SUCCEEDED; a missing or already-terminal entry is ignored. */
    private void succeedQuietly(MutableJob job, int generation, String outcome) {
        AuditJournalService audit = this.journal;
        String entryId = job.attemptAuditEntryIds.remove(generation);
        if (audit == null || entryId == null) {
            return;
        }
        try {
            audit.succeed(entryId, itemOutcomePayload(job, generation, outcome));
        } catch (RuntimeException e) {
            log.debug("BATCH_HASH audit succeed failed for job {} attempt {}: {}", job.jobId, generation,
                    e.toString());
        }
    }

    /** Closes the attempt's entry as FAILED with a bounded code; a missing entry is ignored. */
    private void failQuietly(MutableJob job, int generation, String error) {
        failQuietly(job, generation, error, null);
    }

    /**
     * Closes the attempt's entry as FAILED with a bounded code and, when {@code result} is non-null,
     * the per-item outcome payload so the replay trail records which items were hashed before the
     * batch failed ({@code REQ-03}).
     */
    private void failQuietly(MutableJob job, int generation, String error, Map<String, Object> result) {
        AuditJournalService audit = this.journal;
        String entryId = job.attemptAuditEntryIds.remove(generation);
        if (audit == null || entryId == null) {
            return;
        }
        try {
            if (result == null) {
                audit.fail(entryId, boundedAuditText(error, BATCH_HASH_FAILED));
            } else {
                audit.fail(entryId, boundedAuditText(error, BATCH_HASH_FAILED), result);
            }
        } catch (RuntimeException e) {
            log.debug("BATCH_HASH audit fail failed for job {} attempt {}: {}", job.jobId, generation,
                    e.toString());
        }
    }

    /**
     * Opens and immediately closes one {@code HEARTBEAT} row for the renewed attempt.
     *
     * <p>Best effort by contract, exactly like {@link #beginQuietly}: a journal fault must never
     * change the heartbeat outcome, so every throwable is swallowed and the lease extension stands
     * ({@code INV-HB-7}). The descriptor and the result payload carry no item contents, no secret key
     * and no caller-controlled text; {@code leaseExpiresAt} is journaled as an ISO-8601
     * {@code String} because the journal's {@code ObjectMapper} has no {@code JavaTimeModule}
     * ({@code INV-HB-5}, {@code INV-HB-6}).</p>
     */
    private void recordHeartbeatQuietly(MutableJob job, int generation, Instant expiresAt) {
        AuditJournalService audit = this.journal;
        if (audit == null) {
            return;
        }
        try {
            String id = audit.begin(OperationType.BATCH_HASH,
                    HeartbeatAuditDescriptor.of(job.jobId, generation, expiresAt),
                    RequestContext.get()).id();
            audit.succeed(id, Map.of(
                    "status", HEARTBEAT_STATUS,
                    "detail", HEARTBEAT_DETAIL,
                    "jobId", job.jobId,
                    "attempt", generation,
                    "leaseExpiresAt", expiresAt.toString()));
        } catch (RuntimeException e) {
            log.debug("BATCH_HASH heartbeat audit failed for job {} attempt {}: {}",
                    job.jobId, generation, e.toString());
        }
    }

    /** Clamps client-controlled text so a pathological payload cannot bloat the bounded journal. */
    private static String boundedAuditText(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.length() <= MAX_AUDIT_TEXT ? value : value.substring(0, MAX_AUDIT_TEXT);
    }

    /**
     * The audit descriptor of one attempt. Deliberately payload-free: the job id, attempt number,
     * algorithm, item count and idempotency key describe the work without storing item contents or
     * the secret key in the journal.
     */
    private record BatchHashAuditDescriptor(String jobId, int attempt, String algorithm, int itemCount,
            String idempotencyKey, int resumedFrom, int completedItems) {

        private static BatchHashAuditDescriptor of(MutableJob job, int generation) {
            return new BatchHashAuditDescriptor(job.jobId, generation,
                    boundedAuditText(job.algorithm, "UNKNOWN"), job.items.size(),
                    boundedAuditText(job.idempotencyKey, null),
                    job.attemptResumedFrom.getOrDefault(generation, 0), job.checkpoint.size());
        }
    }

    /**
     * The audit descriptor of one heartbeat. Deliberately payload-free: the job id, attempt number,
     * heartbeat marker and the new expiry describe the renewal without storing item contents or the
     * secret key in the journal.
     */
    private record HeartbeatAuditDescriptor(String jobId, int attempt, String kind, String leaseExpiresAt) {

        private static HeartbeatAuditDescriptor of(String jobId, int generation, Instant expiresAt) {
            return new HeartbeatAuditDescriptor(jobId, generation, HEARTBEAT_STATUS, expiresAt.toString());
        }
    }

    private String describe(Throwable t) {
        return t.getMessage() == null
                ? t.getClass().getName()
                : t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    /** The observable fields of a FAILED job, captured so a rejected requeue can restore them exactly. */
    private static final class FailedState {

        private final int attempt;
        private final Instant startedAt;
        private final Instant completedAt;
        private final String error;
        private final List<HashResponse> results;
        private final SortedMap<Integer, HashResponse> checkpoint;
        private final BitSet failedItems;
        private final Map<Integer, String> itemErrors;
        private final Instant lastHeartbeatAt;
        private final Instant executionLeaseExpiresAt;
        private final Instant deadlineAt;

        private FailedState(int attempt, Instant startedAt, Instant completedAt, String error,
                List<HashResponse> results, SortedMap<Integer, HashResponse> checkpoint,
                BitSet failedItems, Map<Integer, String> itemErrors,
                Instant lastHeartbeatAt, Instant executionLeaseExpiresAt, Instant deadlineAt) {
            this.attempt = attempt;
            this.startedAt = startedAt;
            this.completedAt = completedAt;
            this.error = error;
            this.results = results;
            this.checkpoint = checkpoint;
            this.failedItems = failedItems;
            this.itemErrors = itemErrors;
            this.lastHeartbeatAt = lastHeartbeatAt;
            this.executionLeaseExpiresAt = executionLeaseExpiresAt;
            this.deadlineAt = deadlineAt;
        }

        private static FailedState of(MutableJob job) {
            return new FailedState(job.attempt, job.startedAt, job.completedAt, job.error, job.results,
                    new TreeMap<>(job.checkpoint), (BitSet) job.failedItems.clone(),
                    new HashMap<>(job.itemErrors), job.lastHeartbeatAt, job.executionLeaseExpiresAt,
                    job.deadlineAt);
        }

        private void restore(MutableJob job) {
            job.attempt = attempt;
            job.status = JobStatus.FAILED;
            job.startedAt = startedAt;
            job.completedAt = completedAt;
            job.error = error;
            job.results = results;
            job.checkpoint.clear();
            job.checkpoint.putAll(checkpoint);
            job.failedItems.clear();
            job.failedItems.or(failedItems);
            job.itemErrors.clear();
            job.itemErrors.putAll(itemErrors);
            job.lastHeartbeatAt = lastHeartbeatAt;
            job.executionLeaseExpiresAt = executionLeaseExpiresAt;
            job.deadlineAt = deadlineAt; // a refused requeue must not leave a future deadline behind
        }
    }

    /**
     * Canonical fingerprint of a request payload: the normalised algorithm, the ordered item list
     * and the secret key. Only the SHA-256 digest leaves this method, so no pre-image (and no
     * secret) is ever stored on a job, logged, or returned.
     *
     * <p>Fields are encoded unambiguously — explicit item count plus byte-length-prefixed UTF-8
     * values — so {@code ["ab","c"]} and {@code ["a","bc"]} cannot collide, and input order is
     * significant. The normalisation mirrors {@code CryptoService.hash}: trim, upper-case, then the
     * {@code SHA256}/{@code SHA-256} style aliases, with {@code null} and blank secrets treated as
     * equivalent. An unsupported algorithm is <em>not</em> rejected here — it keeps its trimmed
     * upper-cased spelling so it still reaches the worker and becomes a {@code FAILED} job.</p>
     *
     * <p>The effective deadline is part of a request's identity, appended <em>after</em> the secret
     * key and only when it is enabled, so the same idempotency key with a different bound conflicts
     * while deadline-free traffic canonicalises byte-identically to before. The added bytes are a
     * presence marker plus a length-prefixed field, which no deadline-free encoding can produce, so
     * the encoding stays injective.</p>
     */
    private static String fingerprint(String algorithm, List<String> items, String secretKey,
            long effectiveDeadlineSeconds) {
        String canonicalAlgorithm = normalizeAlgorithmForFingerprint(algorithm);
        boolean secretPresent = secretKey != null && !secretKey.isBlank();
        StringBuilder canonical = new StringBuilder(96 + items.size() * 24);
        appendField(canonical, canonicalAlgorithm);
        canonical.append(items.size()).append('|');
        for (String item : items) {
            appendField(canonical, item);
        }
        canonical.append(secretPresent ? '1' : '0');
        appendField(canonical, secretPresent ? secretKey : "");
        if (effectiveDeadlineSeconds > 0) {
            canonical.append('1');
            appendField(canonical, Long.toString(effectiveDeadlineSeconds));
        }
        byte[] canonicalBytes = canonical.toString().getBytes(StandardCharsets.UTF_8);
        return HexFormat.of().formatHex(sha256(canonicalBytes));
    }

    private static void appendField(StringBuilder out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.append(bytes.length).append(':').append(value).append(';');
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM spec but unavailable", e);
        }
    }

    private static String normalizeAlgorithmForFingerprint(String algorithm) {
        String upper = algorithm.trim().toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "SHA256", "SHA-256" -> "SHA-256";
            case "SHA512", "SHA-512" -> "SHA-512";
            case "SHA1", "SHA-1" -> "SHA-1";
            case "MD5" -> "MD5";
            default -> upper;
        };
    }

    private static final class MutableJob {

        private final String jobId;
        private final String idempotencyKey;
        private final String algorithm;
        private final List<String> items;
        private final String secretKey;
        private final String fingerprint;
        /** Client identity the job's reservation is charged to; never copied into a snapshot or journal. */
        private final String clientKey;
        private final Instant createdAt;
        private final int maxAttempts;
        /** Resolved total-life bound in seconds; {@code 0} means "unbounded" (today's behaviour). */
        private final long deadlineSeconds;
        /**
         * True while this job holds a slot in {@code activeJobsByClient}. Flipped to true when the
         * reservation is taken and claimed ({@code compareAndSet(true, false)}) in the same monitor
         * section that writes the terminal status, so a slot is released exactly once.
         */
        private final AtomicBoolean quotaReserved = new AtomicBoolean(false);
        private final List<JobAttempt> attemptHistory = new ArrayList<>();
        /**
         * Per-attempt correlation id captured on the submit/retry caller thread. Kept per attempt
         * (not per job) so a retry is correlated to the retry caller, and only for the attempts
         * this instance admitted.
         */
        private final Map<Integer, String> attemptRequestIds = new HashMap<>();
        /** Audit entry id of every attempt that actually started, keyed by attempt number. */
        private final Map<Integer, String> attemptAuditEntryIds = new HashMap<>();
        /**
         * Durable per-item checkpoint of this job: the item index is the key and the hashed response
         * the value, so an ascending key walk is the input-order result list and the first absent
         * index is the resume point.
         */
        private final TreeMap<Integer, HashResponse> checkpoint = new TreeMap<>();
        /**
         * Item indices this job has recorded as failed. They are never re-hashed ({@code INV-PI-3}),
         * so a retry skips them exactly like the succeeded ones while the job keeps the evidence.
         */
        private final BitSet failedItems = new BitSet();
        /** Bounded detail of the latest failure of each item index; never carries the item content. */
        private final Map<Integer, String> itemErrors = new HashMap<>();
        /** Item indices each admitted attempt hashed (per-attempt projection for its history row). */
        private final Map<Integer, BitSet> attemptSucceededItems = new HashMap<>();
        /** Item indices each admitted attempt failed (per-attempt projection for its history row). */
        private final Map<Integer, BitSet> attemptFailedItems = new HashMap<>();
        /** Where each admitted attempt started, keyed by attempt number. */
        private final Map<Integer, Integer> attemptResumedFrom = new HashMap<>();

        private int attempt = 1;
        private JobStatus status = JobStatus.QUEUED;
        private Instant startedAt;
        private Instant completedAt;
        private Instant lastHeartbeatAt;
        private Instant executionLeaseExpiresAt;
        /**
         * Absolute total-life bound, frozen at submit ({@code createdAt + deadlineSeconds}) and restarted
         * at retry. {@code null} when the job is unbounded, so no deadline check ever fires.
         */
        private Instant deadlineAt;
        private List<HashResponse> results;
        private String error;

        private MutableJob(String jobId, String idempotencyKey, String algorithm, List<String> items,
                String secretKey, String fingerprint, Instant createdAt, String clientKey, int maxAttempts,
                long deadlineSeconds) {
            this.jobId = jobId;
            this.idempotencyKey = idempotencyKey;
            this.algorithm = algorithm;
            this.items = List.copyOf(items);
            this.secretKey = secretKey;
            this.fingerprint = fingerprint;
            this.createdAt = createdAt;
            this.clientKey = clientKey;
            this.maxAttempts = maxAttempts;
            this.deadlineSeconds = deadlineSeconds;
            this.deadlineAt = deadlineSeconds > 0 ? createdAt.plusSeconds(deadlineSeconds) : null;
        }
    }
}
