package com.example.utiltools.audit.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Bounded result of an operator-triggered journal verification sweep.
 *
 * <p>{@code limit} is the effective limit after clamping to {@code utiltools.audit.max-entries}, so
 * a caller can see the bound that was actually applied. The aggregates are counted from
 * {@code results}, so {@code verified + mismatch + failed + skipped == scanned == results.size()}
 * always holds. No stored request or result payload is ever carried by this DTO.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JournalVerifyResponse(
        int limit,
        int scanned,
        int verified,
        int mismatch,
        int failed,
        int skipped,
        List<JournalVerifyResult> results
) {}
