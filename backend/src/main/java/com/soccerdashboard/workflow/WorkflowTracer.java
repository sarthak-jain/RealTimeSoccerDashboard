package com.soccerdashboard.workflow;

import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class WorkflowTracer {

    private final WorkflowEmitter emitter;
    private final AtomicInteger pollCycleCounter = new AtomicInteger(0);

    public WorkflowTracer(WorkflowEmitter emitter) {
        this.emitter = emitter;
    }

    public Trace startPollCycle() {
        int cycleNumber = pollCycleCounter.incrementAndGet();
        String traceId = "poll-" + cycleNumber;
        emitter.broadcastTraceStart(traceId, "POLL_CYCLE", "Poll Cycle #" + cycleNumber);
        return new Trace(traceId, WorkflowStep.TraceType.POLL_CYCLE, emitter);
    }

    public Trace startUserAction(String action) {
        String traceId = "user-" + UUID.randomUUID().toString().substring(0, 8);
        emitter.broadcastTraceStart(traceId, "USER_ACTION", action);
        return new Trace(traceId, WorkflowStep.TraceType.USER_ACTION, emitter);
    }

    public Trace startSystemEvent(String event) {
        String traceId = "sys-" + UUID.randomUUID().toString().substring(0, 8);
        emitter.broadcastTraceStart(traceId, "SYSTEM_EVENT", event);
        return new Trace(traceId, WorkflowStep.TraceType.SYSTEM_EVENT, emitter);
    }

    public int getCurrentCycleNumber() {
        return pollCycleCounter.get();
    }

    public static class Trace {
        private final String traceId;
        private final WorkflowStep.TraceType traceType;
        private final WorkflowEmitter emitter;
        private final AtomicInteger stepCounter = new AtomicInteger(0);
        private final long startTime = System.nanoTime();

        Trace(String traceId, WorkflowStep.TraceType traceType, WorkflowEmitter emitter) {
            this.traceId = traceId;
            this.traceType = traceType;
            this.emitter = emitter;
        }

        public String getTraceId() { return traceId; }
        public int nextStep() { return stepCounter.incrementAndGet(); }
        public long elapsedMs() { return (System.nanoTime() - startTime) / 1_000_000; }

        public void emit(WorkflowStep step) {
            step.withTraceType(traceType);
            emitter.broadcast(step);
        }

        public void emitApiGateway(String route) {
            emit(WorkflowStep.apiGateway(traceId, nextStep(), route, 1));
        }

        public void emitCacheCheck(String key, WorkflowStep.CacheStatus status, long durationMs) {
            emit(WorkflowStep.cacheCheck(traceId, nextStep(), key, status, durationMs));
        }

        public void emitCacheWrite(String key, String ttl, long durationMs) {
            emit(WorkflowStep.cacheWrite(traceId, nextStep(), key, ttl, durationMs));
        }

        public void emitExternalApi(String service, String detail, int statusCode, long durationMs) {
            emit(WorkflowStep.externalApi(traceId, nextStep(), service, detail, statusCode, durationMs));
        }

        public void emitCircuitBreaker(String state, int failureCount) {
            emit(WorkflowStep.circuitBreaker(traceId, nextStep(), state, failureCount, 0));
        }

        public void emitRateLimit(int remaining, int max) {
            emit(WorkflowStep.rateLimit(traceId, nextStep(), remaining, max, 0));
        }

        public void emitResponse(int statusCode, int resultCount) {
            emit(WorkflowStep.response(traceId, nextStep(), statusCode, resultCount, elapsedMs()));
        }

        public void emitError(String name, String errorMessage, long durationMs) {
            emit(WorkflowStep.error(traceId, nextStep(), name, errorMessage, durationMs));
        }

        // Soccer-specific emit methods

        public void emitPollCycleStart(int cycleNumber, String interval) {
            emit(WorkflowStep.pollCycleStart(traceId, nextStep(), cycleNumber, interval));
        }

        public void emitPollCycleEnd(int cycleNumber, int apisPolled, int matchesUpdated) {
            emit(WorkflowStep.pollCycleEnd(traceId, nextStep(), cycleNumber, apisPolled, matchesUpdated, elapsedMs()));
        }

        public void emitApiQuotaCheck(String apiName, int used, int max) {
            emit(WorkflowStep.apiQuotaCheck(traceId, nextStep(), apiName, used, max));
        }

        public void emitDataDiff(int scoreChanges, int newEvents, int statusChanges) {
            emit(WorkflowStep.dataDiff(traceId, nextStep(), scoreChanges, newEvents, statusChanges));
        }

        public void emitWebSocketFanout(int clientCount, int changesCount) {
            emit(WorkflowStep.webSocketFanout(traceId, nextStep(), clientCount, changesCount));
        }

        public void emitAuthCheck(String username, String detail) {
            emit(WorkflowStep.authCheck(traceId, nextStep(), username, detail));
        }

        public void emitDbWrite(String table, String detail, long durationMs) {
            emit(WorkflowStep.dbWrite(traceId, nextStep(), table, detail, durationMs));
        }

        public void emitDbRead(String table, String detail, long durationMs) {
            emit(WorkflowStep.dbRead(traceId, nextStep(), table, detail, durationMs));
        }

        public void emitSchedulerTick(int connectedClients, String nextPollIn) {
            emit(WorkflowStep.schedulerTick(traceId, nextStep(), connectedClients, nextPollIn));
        }

        public void emitApiSourceSelect(String selectedApi, String reason) {
            emit(WorkflowStep.apiSourceSelect(traceId, nextStep(), selectedApi, reason));
        }

        public void emitLlmInference(String model, String prompt, int inputTokens, int outputTokens, long durationMs) {
            emit(WorkflowStep.llmInference(traceId, nextStep(), model, prompt, inputTokens, outputTokens, durationMs));
        }
    }
}
