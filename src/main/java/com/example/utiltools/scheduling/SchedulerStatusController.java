package com.example.utiltools.scheduling;

import java.util.LinkedHashMap;
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
 * <p>Serving this route must not schedule, cancel, or otherwise mutate any maintenance
 * pass (REQ-03); it only reports the configured shape of the pool plus live thread and
 * queue counters from the underlying {@link java.util.concurrent.ThreadPoolExecutor}.
 */
@Tag(name = "Maintenance Scheduling", description = "Read-only status of the shared maintenance scheduler pool")
@RestController
@RequestMapping("/api/v1/utils/maintenance")
public class SchedulerStatusController {

    private final MaintenanceTaskScheduler taskScheduler;
    private final MaintenanceSchedulingProperties properties;

    public SchedulerStatusController(MaintenanceTaskScheduler taskScheduler,
                                     MaintenanceSchedulingProperties properties) {
        this.taskScheduler = taskScheduler;
        this.properties = properties;
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
}
