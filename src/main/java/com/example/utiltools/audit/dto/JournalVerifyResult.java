package com.example.utiltools.audit.dto;

import com.example.utiltools.audit.model.OperationType;
import com.example.utiltools.audit.model.ReplayState;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One row of a journal verification sweep.
 *
 * <p>Exactly one of {@code outcome} ({@code VERIFIED} / {@code MISMATCH} / {@code FAILED}) and
 * {@code skipReason} ({@code LIVE_CLAIM}, {@code EVICTED}, {@code NOT_SUCCEEDED},
 * {@code NON_DETERMINISTIC}, {@code NOT_REPLAYABLE}, {@code CLAIM_LOST}, {@code CLAIM_CONFLICT})
 * is non-null. {@code detail} is always present and never longer than
 * {@code AuditReplayService.MAX_DETAIL_LENGTH} characters, and it is derived only from the
 * mismatch pointer or an exception message — the stored request and result payloads, the owner
 * token and the lease token are never carried by this DTO.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JournalVerifyResult(
        String entryId,
        OperationType operation,
        ReplayState outcome,
        String detail,
        String skipReason
) {}
