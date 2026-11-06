package com.example.utiltools.audit.service;

import com.example.utiltools.audit.model.AuditEntry;

import java.time.Instant;

/**
 * Result of a successful {@link AuditJournalService#claimReplay(String)}: the entry as it now looks
 * (with an {@code IN_PROGRESS} replay record) plus the opaque owner token the caller must present to
 * {@link AuditJournalService#completeReplay}.
 *
 * <p>The token is deliberately kept out of the journal entry and out of the {@code ownerToken}
 * serialization surface: it is never stored in an {@link AuditEntry}, never written by
 * {@code list}/{@code find}, and never returned by a controller.
 */
public record ReplayClaim(
        AuditEntry entry,
        String ownerToken,
        Instant leaseExpiresAt
) {}
