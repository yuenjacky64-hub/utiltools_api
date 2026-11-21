package com.example.utiltools.scheduling;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.example.utiltools.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only operator endpoint for the shared maintenance scheduler pool.
 *
 * <p>Serving these routes must not schedule, cancel, or otherwise mutate any maintenance
 * pass (REQ-03 / REQ-05); they only report the configured shape of the pool plus live thread and
 * queue counters from the underlying {@link java.util.concurrent.ThreadPoolExecutor}, and the
 * per-pass quarantine state held by {@link MaintenancePassGuard}.
 */
@Tag(name = "Maintenance Scheduling", description = "Read-only status of the shared maintenance scheduler pool")
@RestController
@RequestMapping("/api/v1/utils/maintenance")
public class SchedulerStatusController {

    private final MaintenanceTaskScheduler taskScheduler;
    private final MaintenanceSchedulingProperties properties;
    private final MaintenancePassGuard guard;

    public SchedulerStatusController(MaintenanceTaskScheduler taskScheduler,
                                     MaintenanceSchedulingProperties properties,
                                     MaintenancePassGuard guard) {
        this.taskScheduler = taskScheduler;
        this.properties = properties;
        this.guard = guard;
    }

    @Operation(summary = "Live status of the shared maintenance scheduler pool")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                description = "Scheduler status snapshot")
    })
    @GetMapping("/scheduler")
    public ApiResponse<Map<String, Object>> schedulerStatus() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("poolSize", properties.poolSize());
        body.put("threadNamePrefix", properties.threadNamePrefix());
        body.put("awaitTerminationSeconds", properties.awaitTerminationSeconds());
        body.put("activeThreadCount", taskScheduler.activeCount());
        body.put("completedTaskCount", taskScheduler.completedTaskCount());
        body.put("queueDepth", taskScheduler.queueDepth());
        return ApiResponse.success(body);
    }

    @Operation(summary = "Per-pass quarantine state, counters and recent breaker transitions")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                description = "One row per maintenance pass, plus the bounded recent transitions")
    })
    @GetMapping("/passes")
    public ApiResponse<MaintenancePassesResponse> maintenancePasses() {
        List<MaintenancePassesResponse.Row> rows = guard.snapshot().stream()
                .map(p -> new MaintenancePassesResponse.Row(p.pass().name(), p.state(),
                        p.consecutiveFailures(), p.lastOutcome().name(), p.lastFailureAt(),
                        p.lastSuccessAt(), p.nextProbeAt(), p.succeeded(), p.failed(), p.skipped()))
                .toList();
        List<MaintenancePassesResponse.Transition> recent = guard
                .recentTransitions(MaintenancePassGuard.MAX_RECENT_TRANSITIONS).stream()
                .map(e -> new MaintenancePassesResponse.Transition(e.timestamp(), e.fingerprint(),
                        e.from(), e.to(), e.trigger()))
                .toList();
        return ApiResponse.success(new MaintenancePassesResponse(rows, guard.auditSize(),
                guard.auditCapacity(), recent));
    }
}
