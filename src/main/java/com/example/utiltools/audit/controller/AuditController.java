package com.example.utiltools.audit.controller;

import com.example.utiltools.audit.dto.JournalReapResponse;
import com.example.utiltools.audit.dto.JournalVerifyResponse;
import com.example.utiltools.audit.dto.QuarantineStatusResponse;
import com.example.utiltools.audit.model.AuditEntry;
import com.example.utiltools.audit.model.JournalState;
import com.example.utiltools.audit.model.ReapReason;
import com.example.utiltools.audit.model.ReapRecord;
import com.example.utiltools.audit.service.AuditJournalService;
import com.example.utiltools.audit.service.AuditReplayService;
import com.example.utiltools.common.ApiResponse;
import com.example.utiltools.common.AuditEntryNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/utils/audit")
@Tag(name = "Audit Journal", description = "Bounded in-memory operation journal with request correlation and single-winner replay verification: "
        + "a replay claims the entry (IN_PROGRESS), records VERIFIED, MISMATCH or FAILED, and answers "
        + "409 REPLAY_CONFLICT while another claim is live or when the caller's claim was lost. A running "
        + "replay renews its own claim under the journal lock every "
        + "utiltools.audit.replay-heartbeat-seconds, so a long invocation outlives the lease instead of "
        + "losing it; renewal is owner-fenced and never exposes the owner token. "
        + "POST /reaps runs one bounded manual reap pass (with dry-run preview and single-entry manual "
        + "reclaim) under the same lock as the scheduled reaper")
public class AuditController {

    private final AuditJournalService journal;
    private final AuditReplayService replayService;

    public AuditController(AuditJournalService journal, AuditReplayService replayService) {
        this.journal = journal;
        this.replayService = replayService;
    }

    @GetMapping("/entries")
    @Operation(summary = "List journal entries", description = "Returns the newest entries first, optionally filtered by state")
    public ApiResponse<List<AuditEntry>> list(
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit,
            @RequestParam(value = "state", required = false) JournalState state) {
        return ApiResponse.success(journal.list(limit, state), "Audit entries retrieved successfully");
    }

    @GetMapping("/entries/{id}")
    @Operation(summary = "Get a single journal entry", description = "Returns one entry or 404 NOT_FOUND")
    public ApiResponse<AuditEntry> find(@PathVariable String id) {
        AuditEntry entry = journal.find(id).orElseThrow(() -> new AuditEntryNotFoundException(id));
        return ApiResponse.success(entry, "Audit entry retrieved successfully");
    }

    @PostMapping("/entries/{id}/replay")
    @Operation(summary = "Replay a journal entry", description = """
            Claims the entry atomically (replay.state IN_PROGRESS with leaseExpiresAt), re-runs the
            deterministic SUCCEEDED operation, then records VERIFIED or MISMATCH — or FAILED with a
            bounded detail when the invocation throws. The original state, result and completedAt are
            never altered. While the invocation runs, this owner renews its claim under the journal
            lock every utiltools.audit.replay-heartbeat-seconds (default half of
            utiltools.audit.replay-lease-seconds, minimum 1 second), so an operation that outlives the
            lease still completes VERIFIED or MISMATCH and produces no REPLAY_LEASE_EXPIRED tombstone;
            renewal stops in a finally block and a stale, terminal or expired claim is never extended.
            Answers 409 REPLAY_CONFLICT when another replay holds a live claim, when the caller's claim
            was lost, or when the entry is not replayable; a crashed replayer's IN_PROGRESS claim is
            taken over once utiltools.audit.replay-lease-seconds elapses. Only leaseExpiresAt is
            exposed on the replay record.""")
    public ApiResponse<AuditEntry> replay(@PathVariable String id) {
        return ApiResponse.success(replayService.replay(id), "Replay verification completed");
    }

    @PostMapping("/verify")
    @Operation(summary = "Sweep-verify the journal",
               description = "Replays up to `limit` (default 20, clamped to utiltools.audit.max-entries) "
                   + "newest SUCCEEDED entries and records VERIFIED / MISMATCH / FAILED per entry. Bounded: at most "
                   + "max-entries entries per call. Single-winner per entry: each entry is claimed through the same "
                   + "claimReplay lease as POST /entries/{id}/replay, so a live claim is reported SKIPPED with reason "
                   + "LIVE_CLAIM and its lease is untouched, each claim is renewed by its owner while the invocation "
                   + "runs (utiltools.audit.replay-heartbeat-seconds), and a failed invocation is recorded FAILED without "
                   + "aborting the sweep. Safe alongside the reaper and eviction: the sweep holds no journal lock while "
                   + "an operation runs, an entry evicted mid-sweep is reported SKIPPED with reason EVICTED, and the "
                   + "original state, result, completedAt, request and replayable flag are never rewritten.")
    public ApiResponse<JournalVerifyResponse> verify(
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit) {
        return ApiResponse.success(replayService.verify(limit), "Journal verification sweep completed");
    }

    @PostMapping("/reaps")
    @Operation(summary = "Run a journal reap pass",
               description = """
                       Runs exactly one bounded, synchronous reaper pass and answers its counts
                       (`scanned`, `reaped` and one count per reason: `replayLeaseExpired`,
                       `manuallyReaped`, `stalePending`, `oversizePayload`), so an operator can preview
                       or force the sweep the scheduled reaper runs. Shares the journal lock with the
                       scheduled pass: the two are serialized, each eligible entry is reaped at most
                       once per pass and exactly one tombstone is appended per reaped entry. `dryRun=true`
                       uses the same eligibility classification but mutates nothing — no
                       PENDING-to-FAILED transition, no replay-claim rollback, no tombstone, no manual
                       intent consumed and no eviction. An entry whose replay claim lease expired
                       utiltools.audit.replay-lease-quarantine-threshold times is failed for good with reason
                       REPLAY_LEASE_QUARANTINED and skipped by every later pass; that tombstone is counted in
                       `replayLeaseExpired`, so `reaped` stays the sum of the four counts. `entryId` targets one PENDING entry: intent is
                       recorded under the lock and consumed by this pass, an unknown id answers 404
                       NOT_FOUND and an already-terminal entry answers 409 REAP_CONFLICT without
                       mutating it, consuming intent or writing a tombstone. The endpoint appends no
                       audit journal entry (no recursive journalling) and no request, result, owner
                       token or lease deadline is returned.""")
    public ApiResponse<JournalReapResponse> reap(
            @RequestParam(value = "dryRun", required = false, defaultValue = "false") boolean dryRun,
            @RequestParam(value = "entryId", required = false) String entryId) {
        return ApiResponse.success(journal.reapManually(entryId, dryRun), "Manual reap pass completed");
    }

    @GetMapping("/reaps")
    @Operation(summary = "List reaper tombstones",
               description = "Returns the newest reaped-entry tombstones first, optionally filtered by reason. "
                   + "Bounded: `limit` (default 50) is clamped to utiltools.audit.max-entries. Read-only — no "
                   + "reap pass runs and no entry, tombstone or journal record is created or changed. An unknown "
                   + "reason answers 400 BAD_REQUEST.")
    public ApiResponse<List<ReapRecord>> reaps(
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit,
            @RequestParam(value = "reason", required = false) ReapReason reason) {
        return ApiResponse.success(journal.reapLog(limit, reason), "Reap tombstones retrieved successfully");
    }

    @GetMapping("/quarantine-status")
    @Operation(summary = "Get replay-lease quarantine status",
               description = """
                       Returns the effective `utiltools.audit.replay-lease-quarantine-threshold` and two
                       counters: how many bounded `REPLAY_LEASE_QUARANTINED` tombstones the journal has
                       recorded, and how many times replay claim leases have expired across the journal's
                       entries (the counter that drives quarantine). Read-only — no reap pass runs and no
                       entry, replay claim or tombstone is created or changed. Counts only: no request,
                       result, replay owner token or lease deadline is returned.""")
    public ApiResponse<QuarantineStatusResponse> quarantineStatus() {
        return ApiResponse.success(journal.quarantineStatus(), "Quarantine status retrieved successfully");
    }
}
