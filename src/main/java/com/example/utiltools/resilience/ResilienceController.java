package com.example.utiltools.resilience;

import com.example.utiltools.common.ApiResponse;
import com.example.utiltools.json.dto.JsonQueryRequest;
import com.example.utiltools.json.service.JsonService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/utils/resilience")
@Tag(name = "Resilience & Circuit Breaker Utility", description = "Endpoints for inspecting the JSONPath circuit breaker state and its transition audit trail")
public class ResilienceController {

    private static final String DEFAULT_PROBE_JSON = "{\"probe\":true}";
    private static final String DEFAULT_PROBE_PATH = "$.probe";

    private final CircuitBreaker circuitBreaker;
    private final JsonService jsonService;

    public ResilienceController(CircuitBreaker circuitBreaker, JsonService jsonService) {
        this.circuitBreaker = circuitBreaker;
        this.jsonService = jsonService;
    }

    @GetMapping
    @Operation(summary = "Inspect circuit breaker state", description = "Returns the live breaker state, consecutive failure/success counters, the cooldown reset time, and the most recent transition audit entries")
    public ApiResponse<ResilienceResponse> state(@RequestParam(defaultValue = "20") int limit) {
        int bounded = Math.max(0, Math.min(limit, circuitBreaker.auditTrail().capacity()));
        ResilienceResponse response = new ResilienceResponse(
                circuitBreaker.name(),
                circuitBreaker.state(),
                circuitBreaker.consecutiveFailures(),
                circuitBreaker.consecutiveSuccesses(),
                circuitBreaker.resetAt(),
                circuitBreaker.auditTrail().capacity(),
                circuitBreaker.auditTrail().recent(bounded));
        return ApiResponse.success(response, "Resilience state retrieved");
    }

    @PostMapping("/probe")
    @Operation(summary = "Force a trial call in the HALF_OPEN window", description = "Drives one guarded JSONPath call: success closes the breaker once the success threshold is met, failure returns it to OPEN and is recorded with trigger PROBE_FAILED")
    public ApiResponse<CircuitBreaker.ProbeResult> probe(@RequestBody(required = false) ProbeRequest request) {
        String json = request == null || request.json() == null || request.json().isBlank()
                ? DEFAULT_PROBE_JSON : request.json();
        String jsonPath = request == null || request.jsonPath() == null || request.jsonPath().isBlank()
                ? DEFAULT_PROBE_PATH : request.jsonPath();
        CircuitBreaker.ProbeResult result =
                circuitBreaker.probe(() -> jsonService.query(new JsonQueryRequest(json, jsonPath)));
        return ApiResponse.success(result, result.probeSucceeded() ? "Probe succeeded" : "Probe failed");
    }
}
