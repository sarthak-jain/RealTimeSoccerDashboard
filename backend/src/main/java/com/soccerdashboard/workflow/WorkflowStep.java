package com.soccerdashboard.workflow;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.LinkedHashMap;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkflowStep {

    public enum StepType {
        API_GATEWAY,
        CACHE_CHECK,
        CACHE_WRITE,
        EXTERNAL_API,
        CIRCUIT_BREAKER,
        RATE_LIMIT,
        RESPONSE,
        ERROR,
        // Soccer-specific step types
        POLL_CYCLE_START,
        POLL_CYCLE_END,
        API_QUOTA_CHECK,
        DATA_DIFF,
        WEBSOCKET_FANOUT,
        AUTH_CHECK,
        DB_WRITE,
        DB_READ,
        SCHEDULER_TICK,
        API_SOURCE_SELECT,
        SUBSCRIPTION_UPDATE,
        LLM_INFERENCE
    }

    public enum TraceType {
        POLL_CYCLE,
        USER_ACTION,
        SYSTEM_EVENT
    }

    public enum CacheStatus {
        HIT, MISS, SKIP
    }

    private int stepNumber;
    private StepType type;
    private String name;
    private String detail;
    private long durationMs;
    private CacheStatus cacheStatus;
    private String query;
    private Integer resultCount;
    private Integer statusCode;
    private String errorMessage;
    private String traceId;
    private TraceType traceType;
    private long timestamp;
    private Map<String, String> metadata;

    public WorkflowStep() {
        this.timestamp = System.currentTimeMillis();
    }

    // --- Factory methods from MovieFinder ---

    public static WorkflowStep apiGateway(String traceId, int stepNumber, String route, long durationMs) {
        WorkflowStep step = new WorkflowStep();
        step.traceId = traceId;
        step.stepNumber = stepNumber;
        step.type = StepType.API_GATEWAY;
        step.name = "API Gateway";
        step.detail = route;
        step.durationMs = durationMs;
        return step;
    }

    public static WorkflowStep cacheCheck(String traceId, int stepNumber, String key, CacheStatus status, long durationMs) {
        WorkflowStep step = new WorkflowStep();
        step.traceId = traceId;
        step.stepNumber = stepNumber;
        step.type = StepType.CACHE_CHECK;
        step.name = "Cache " + status;
        step.detail = String.format("Redis LOOKUP %s → %s", key, status);
        step.cacheStatus = status;
        step.durationMs = durationMs;
        step.metadata = new LinkedHashMap<>();
        step.metadata.put("Key", key);
        return step;
    }

    public static WorkflowStep cacheWrite(String traceId, int stepNumber, String key, String ttl, long durationMs) {
        WorkflowStep step = new WorkflowStep();
        step.traceId = traceId;
        step.stepNumber = stepNumber;
        step.type = StepType.CACHE_WRITE;
        step.name = "Cache Write";
        step.detail = String.format("Persisting to Redis (key: %s, TTL: %s)", key, ttl);
        step.durationMs = durationMs;
        step.metadata = new LinkedHashMap<>();
        step.metadata.put("Key", key);
        step.metadata.put("TTL", ttl);
        return step;
    }

    public static WorkflowStep externalApi(String traceId, int stepNumber, String service, String detail,
                                           int statusCode, long durationMs) {
        WorkflowStep step = new WorkflowStep();
        step.traceId = traceId;
        step.stepNumber = stepNumber;
        step.type = StepType.EXTERNAL_API;
        step.name = service + " API";
        step.detail = detail;
        step.statusCode = statusCode;
        step.durationMs = durationMs;
        step.metadata = new LinkedHashMap<>();
        step.metadata.put("Source", service);
        step.metadata.put("Status", String.valueOf(statusCode));
        return step;
    }

    public static WorkflowStep circuitBreaker(String traceId, int stepNumber, String state, int failureCount, long durationMs) {
        WorkflowStep step = new WorkflowStep();
        step.traceId = traceId;
        step.stepNumber = stepNumber;
        step.type = StepType.CIRCUIT_BREAKER;
        step.name = "Circuit Breaker";
        step.detail = String.format("State: %s (failures: %d/3)", state, failureCount);
        step.metadata = new LinkedHashMap<>();
        step.metadata.put("CB", state);
        step.metadata.put("Failures", String.valueOf(failureCount));
        step.durationMs = durationMs;
        return step;
    }

    public static WorkflowStep rateLimit(String traceId, int stepNumber, int remaining, int max, long durationMs) {
        WorkflowStep step = new WorkflowStep();
        step.traceId = traceId;
        step.stepNumber = stepNumber;
        step.type = StepType.RATE_LIMIT;
        step.name = "Rate Limiter";
        step.detail = String.format("Quota: %d/%d remaining in window", remaining, max);
        step.metadata = new LinkedHashMap<>();
        step.metadata.put("Rate", remaining + "/" + max);
        step.durationMs = durationMs;
        return step;
    }

    public static WorkflowStep response(String traceId, int stepNumber, int statusCode, int resultCount, long totalMs) {
        WorkflowStep step = new WorkflowStep();
        step.traceId = traceId;
        step.stepNumber = stepNumber;
        step.type = StepType.RESPONSE;
        step.name = "Response";
        step.detail = String.format("%d OK — %d results (total: %dms)", statusCode, resultCount, totalMs);
        step.statusCode = statusCode;
        step.resultCount = resultCount;
        step.durationMs = totalMs;
        return step;
    }

    public static WorkflowStep error(String traceId, int stepNumber, String name, String errorMessage, long durationMs) {
        WorkflowStep step = new WorkflowStep();
        step.traceId = traceId;
        step.stepNumber = stepNumber;
        step.type = StepType.ERROR;
        step.name = name;
        step.detail = errorMessage;
        step.errorMessage = errorMessage;
        step.durationMs = durationMs;
        return step;
    }

    // --- Soccer Dashboard new factory methods ---

    public static WorkflowStep pollCycleStart(String traceId, int stepNumber, int cycleNumber, String interval) {
        WorkflowStep step = new WorkflowStep();
        step.traceId = traceId;
        step.stepNumber = stepNumber;
        step.type = StepType.POLL_CYCLE_START;
        step.name = "Poll Cycle #" + cycleNumber;
        step.detail = "Polling cycle started (interval: " + interval + ")";
        step.durationMs = 0;
        step.metadata = new LinkedHashMap<>();
        step.metadata.put("Cycle", String.valueOf(cycleNumber));
        step.metadata.put("Interval", interval);
        return step;
    }

    public static WorkflowStep pollCycleEnd(String traceId, int stepNumber, int cycleNumber,
                                            int apisPolled, int matchesUpdated, long totalMs) {
        WorkflowStep step = new WorkflowStep();
        step.traceId = traceId;
        step.stepNumber = stepNumber;
        step.type = StepType.POLL_CYCLE_END;
        step.name = "Cycle #" + cycleNumber + " Complete";
        step.detail = String.format("Cycle #%d complete: %d APIs polled, %d matches updated (%dms)",
                cycleNumber, apisPolled, matchesUpdated, totalMs);
        step.durationMs = totalMs;
        step.metadata = new LinkedHashMap<>();
        step.metadata.put("APIs", String.valueOf(apisPolled));
        step.metadata.put("Updates", String.valueOf(matchesUpdated));
        return step;
    }

    public static WorkflowStep apiQuotaCheck(String traceId, int stepNumber, String apiName,
                                             int used, int max) {
        WorkflowStep step = new WorkflowStep();
        step.traceId = traceId;
        step.stepNumber = stepNumber;
        step.type = StepType.API_QUOTA_CHECK;
        step.name = "API Quota Check";
        step.detail = String.format("%s: %d/%d requests used", apiName, used, max);
        step.durationMs = 0;
        step.metadata = new LinkedHashMap<>();
        step.metadata.put("API", apiName);
        step.metadata.put("Used", used + "/" + max);
        return step;
    }

    public static WorkflowStep dataDiff(String traceId, int stepNumber, int scoreChanges,
                                        int newEvents, int statusChanges) {
        WorkflowStep step = new WorkflowStep();
        step.traceId = traceId;
        step.stepNumber = stepNumber;
        step.type = StepType.DATA_DIFF;
        step.name = "Data Diff";
        step.detail = String.format("Detected %d score changes, %d new events, %d status changes",
                scoreChanges, newEvents, statusChanges);
        step.durationMs = 0;
        step.metadata = new LinkedHashMap<>();
        step.metadata.put("Scores", String.valueOf(scoreChanges));
        step.metadata.put("Events", String.valueOf(newEvents));
        step.metadata.put("Status", String.valueOf(statusChanges));
        return step;
    }

    public static WorkflowStep webSocketFanout(String traceId, int stepNumber, int clientCount,
                                               int changesCount) {
        WorkflowStep step = new WorkflowStep();
        step.traceId = traceId;
        step.stepNumber = stepNumber;
        step.type = StepType.WEBSOCKET_FANOUT;
        step.name = "WebSocket Fanout";
        step.detail = String.format("Broadcasting %d changes to %d connected clients",
                changesCount, clientCount);
        step.durationMs = 0;
        step.metadata = new LinkedHashMap<>();
        step.metadata.put("Clients", String.valueOf(clientCount));
        step.metadata.put("Changes", String.valueOf(changesCount));
        return step;
    }

    public static WorkflowStep authCheck(String traceId, int stepNumber, String username, String detail) {
        WorkflowStep step = new WorkflowStep();
        step.traceId = traceId;
        step.stepNumber = stepNumber;
        step.type = StepType.AUTH_CHECK;
        step.name = "Auth Check";
        step.detail = String.format("JWT validated for user %s (%s)", username, detail);
        step.durationMs = 0;
        step.metadata = new LinkedHashMap<>();
        step.metadata.put("User", username);
        return step;
    }

    public static WorkflowStep dbWrite(String traceId, int stepNumber, String table, String detail, long durationMs) {
        WorkflowStep step = new WorkflowStep();
        step.traceId = traceId;
        step.stepNumber = stepNumber;
        step.type = StepType.DB_WRITE;
        step.name = "DB Write";
        step.detail = String.format("%s → %s", table, detail);
        step.durationMs = durationMs;
        step.metadata = new LinkedHashMap<>();
        step.metadata.put("Table", table);
        return step;
    }

    public static WorkflowStep dbRead(String traceId, int stepNumber, String table, String detail, long durationMs) {
        WorkflowStep step = new WorkflowStep();
        step.traceId = traceId;
        step.stepNumber = stepNumber;
        step.type = StepType.DB_READ;
        step.name = "DB Read";
        step.detail = String.format("%s → %s", table, detail);
        step.durationMs = durationMs;
        step.metadata = new LinkedHashMap<>();
        step.metadata.put("Table", table);
        return step;
    }

    public static WorkflowStep schedulerTick(String traceId, int stepNumber, int connectedClients,
                                             String nextPollIn) {
        WorkflowStep step = new WorkflowStep();
        step.traceId = traceId;
        step.stepNumber = stepNumber;
        step.type = StepType.SCHEDULER_TICK;
        step.name = "Scheduler Tick";
        step.detail = String.format("Clients: %d, Next poll: %s", connectedClients, nextPollIn);
        step.durationMs = 0;
        step.metadata = new LinkedHashMap<>();
        step.metadata.put("Clients", String.valueOf(connectedClients));
        step.metadata.put("Next", nextPollIn);
        return step;
    }

    public static WorkflowStep apiSourceSelect(String traceId, int stepNumber, String selectedApi, String reason) {
        WorkflowStep step = new WorkflowStep();
        step.traceId = traceId;
        step.stepNumber = stepNumber;
        step.type = StepType.API_SOURCE_SELECT;
        step.name = "API Source Select";
        step.detail = String.format("Selected %s (%s)", selectedApi, reason);
        step.durationMs = 0;
        step.metadata = new LinkedHashMap<>();
        step.metadata.put("Source", selectedApi);
        step.metadata.put("Reason", reason);
        return step;
    }

    public static WorkflowStep llmInference(String traceId, int stepNumber, String model, String prompt,
                                               int inputTokens, int outputTokens, long durationMs) {
        WorkflowStep step = new WorkflowStep();
        step.traceId = traceId;
        step.stepNumber = stepNumber;
        step.type = StepType.LLM_INFERENCE;
        step.name = "LLM Inference";
        step.detail = String.format("Model: %s — %d input tokens, %d output tokens", model, inputTokens, outputTokens);
        step.durationMs = durationMs;
        step.metadata = new LinkedHashMap<>();
        step.metadata.put("Model", model);
        step.metadata.put("Input", inputTokens + " tokens");
        step.metadata.put("Output", outputTokens + " tokens");
        step.metadata.put("Prompt", prompt.length() > 80 ? prompt.substring(0, 80) + "..." : prompt);
        return step;
    }

    public WorkflowStep withMeta(String key, String value) {
        if (this.metadata == null) {
            this.metadata = new LinkedHashMap<>();
        }
        this.metadata.put(key, value);
        return this;
    }

    public WorkflowStep withTraceType(TraceType traceType) {
        this.traceType = traceType;
        return this;
    }

    // Getters and setters
    public int getStepNumber() { return stepNumber; }
    public void setStepNumber(int stepNumber) { this.stepNumber = stepNumber; }
    public StepType getType() { return type; }
    public void setType(StepType type) { this.type = type; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
    public CacheStatus getCacheStatus() { return cacheStatus; }
    public void setCacheStatus(CacheStatus cacheStatus) { this.cacheStatus = cacheStatus; }
    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }
    public Integer getResultCount() { return resultCount; }
    public void setResultCount(Integer resultCount) { this.resultCount = resultCount; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public TraceType getTraceType() { return traceType; }
    public void setTraceType(TraceType traceType) { this.traceType = traceType; }
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    public Integer getStatusCode() { return statusCode; }
    public void setStatusCode(Integer statusCode) { this.statusCode = statusCode; }
    public Map<String, String> getMetadata() { return metadata; }
    public void setMetadata(Map<String, String> metadata) { this.metadata = metadata; }
}
