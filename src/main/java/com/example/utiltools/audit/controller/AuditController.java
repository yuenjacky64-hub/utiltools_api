package com.example.utiltools.audit.controller;

import com.example.utiltools.audit.model.AuditEntry;
import com.example.utiltools.audit.model.JournalState;
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
@Tag(name = "Audit Journal", description = "Bounded in-memory operation journal with request correlation and replay verification")
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
    @Operation(summary = "Replay a journal entry", description = "Re-runs a deterministic SUCCEEDED entry and returns VERIFIED or MISMATCH without altering the original entry")
    public ApiResponse<AuditEntry> replay(@PathVariable String id) {
        return ApiResponse.success(replayService.replay(id), "Replay verification completed");
    }
}
