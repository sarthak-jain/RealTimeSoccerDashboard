package com.soccerdashboard.service;

import com.soccerdashboard.model.Match;
import com.soccerdashboard.workflow.WorkflowEmitter;
import com.soccerdashboard.workflow.WorkflowTracer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class PollingScheduler {

    private static final Logger log = LoggerFactory.getLogger(PollingScheduler.class);

    private final FootballDataService footballDataService;
    private final WebSocketBroadcaster webSocketBroadcaster;
    private final WorkflowTracer workflowTracer;
    private final WorkflowEmitter workflowEmitter;
    private final RedisTemplate<String, Object> redisTemplate;
    private final DemoService demoService;
    private final Timer pollCycleTimer;
    private final Counter dataDiffCounter;

    private final AtomicReference<List<Match>> previousMatches = new AtomicReference<>(new ArrayList<>());
    private final AtomicBoolean demoMode = new AtomicBoolean(false);

    public PollingScheduler(FootballDataService footballDataService,
                            WebSocketBroadcaster webSocketBroadcaster,
                            WorkflowTracer workflowTracer,
                            WorkflowEmitter workflowEmitter,
                            RedisTemplate<String, Object> redisTemplate,
                            DemoService demoService,
                            MeterRegistry meterRegistry) {
        this.footballDataService = footballDataService;
        this.webSocketBroadcaster = webSocketBroadcaster;
        this.workflowTracer = workflowTracer;
        this.workflowEmitter = workflowEmitter;
        this.redisTemplate = redisTemplate;
        this.demoService = demoService;
        this.pollCycleTimer = Timer.builder("polling.cycle_duration").register(meterRegistry);
        this.dataDiffCounter = Counter.builder("polling.data_diff_count").register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${app.polling.live-interval-ms:30000}")
    public void pollLiveScores() {
        pollCycleTimer.record(this::doPollLiveScores);
    }

    private void doPollLiveScores() {
        int cycleNumber = workflowTracer.getCurrentCycleNumber() + 1;
        WorkflowTracer.Trace trace = workflowTracer.startPollCycle();

        int totalClients = webSocketBroadcaster.getSessionCount() + workflowEmitter.getActiveClientCount();
        trace.emitSchedulerTick(totalClients, "30s");

        // Skip if no clients connected
        if (totalClients == 0) {
            trace.emitPollCycleStart(cycleNumber, "30s");
            trace.emitPollCycleEnd(cycleNumber, 0, 0);
            return;
        }

        trace.emitPollCycleStart(cycleNumber, "30s");

        // API quota check
        int remaining = footballDataService.getRateLimiter().getRemainingQuota();
        trace.emitApiQuotaCheck("Football-Data.org",
                footballDataService.getRateLimiter().getUsedQuota(), 10);

        if (remaining <= 0) {
            trace.emitRateLimit(0, 10);
            trace.emitPollCycleEnd(cycleNumber, 0, 0);
            return;
        }

        // Select source
        trace.emitApiSourceSelect("Football-Data.org", "Primary source, quota: " + remaining + "/10");

        // Fetch live data
        List<Match> currentMatches;
        if (demoMode.get()) {
            currentMatches = demoService.getNextDemoFrame();
            trace.emitExternalApi("Demo Mode", "Loaded demo frame with " + currentMatches.size() + " matches", 200, 1);
        } else {
            long apiStart = System.nanoTime();
            try {
                currentMatches = footballDataService.getLiveMatches();
                long apiMs = (System.nanoTime() - apiStart) / 1_000_000;
                trace.emitExternalApi("Football-Data.org",
                        "Fetched " + currentMatches.size() + " live matches", 200, apiMs);
            } catch (Exception e) {
                long apiMs = (System.nanoTime() - apiStart) / 1_000_000;
                trace.emitError("Football-Data.org", e.getMessage(), apiMs);
                trace.emitPollCycleEnd(cycleNumber, 1, 0);
                return;
            }
        }

        // Data diff
        DataDiffEngine.DataDiff diff = DataDiffEngine.diff(previousMatches.get(), currentMatches);
        trace.emitDataDiff(diff.getScoreChanges().size(), 0, diff.getStatusChanges().size());
        dataDiffCounter.increment(diff.getTotalChanges());

        // Update cache
        long cacheStart = System.nanoTime();
        try {
            redisTemplate.opsForValue().set("live:scores:all", currentMatches, 30, TimeUnit.SECONDS);
            long cacheMs = (System.nanoTime() - cacheStart) / 1_000_000;
            trace.emitCacheWrite("live:scores:all", "30s", cacheMs);
        } catch (Exception e) {
            log.warn("Failed to cache live scores", e);
        }

        // WebSocket fanout
        int wsClients = webSocketBroadcaster.getSessionCount();
        if (diff.hasChanges() && wsClients > 0) {
            webSocketBroadcaster.broadcastDiff(diff);
            trace.emitWebSocketFanout(wsClients, diff.getTotalChanges());
        } else {
            trace.emitWebSocketFanout(wsClients, 0);
        }

        // Store for next diff
        previousMatches.set(currentMatches);

        trace.emitPollCycleEnd(cycleNumber, 1, diff.getTotalChanges());
    }

    public boolean isDemoMode() { return demoMode.get(); }
    public void setDemoMode(boolean enabled) { demoMode.set(enabled); }
}
