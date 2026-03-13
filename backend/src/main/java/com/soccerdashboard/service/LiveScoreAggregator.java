package com.soccerdashboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soccerdashboard.model.Match;
import com.soccerdashboard.resilience.CircuitBreaker;
import com.soccerdashboard.workflow.WorkflowStep;
import com.soccerdashboard.workflow.WorkflowTracer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class LiveScoreAggregator {

    private static final Logger log = LoggerFactory.getLogger(LiveScoreAggregator.class);

    private final FootballDataService footballDataService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    private final WorkflowTracer workflowTracer;
    private final Counter cacheHitCounter;
    private final Counter cacheMissCounter;

    public LiveScoreAggregator(FootballDataService footballDataService,
                               RedisTemplate<String, Object> redisTemplate,
                               ObjectMapper objectMapper,
                               WorkflowTracer workflowTracer,
                               MeterRegistry meterRegistry) {
        this.footballDataService = footballDataService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.workflowTracer = workflowTracer;
        this.cacheHitCounter = Counter.builder("cache.operations")
                .tag("operation", "hit").tag("key_pattern", "live_scores")
                .register(meterRegistry);
        this.cacheMissCounter = Counter.builder("cache.operations")
                .tag("operation", "miss").tag("key_pattern", "live_scores")
                .register(meterRegistry);
    }

    public List<Match> getLiveMatches(WorkflowTracer.Trace trace) {
        // Check cache first
        long cacheStart = System.nanoTime();
        Object cached = redisTemplate.opsForValue().get("live:scores:all");
        long cacheMs = (System.nanoTime() - cacheStart) / 1_000_000;

        if (cached != null) {
            trace.emitCacheCheck("live:scores:all", WorkflowStep.CacheStatus.HIT, cacheMs);
            cacheHitCounter.increment();
            try {
                String json = objectMapper.writeValueAsString(cached);
                return Arrays.asList(objectMapper.readValue(json, Match[].class));
            } catch (Exception e) {
                log.warn("Failed to deserialize cached live scores", e);
            }
        }
        trace.emitCacheCheck("live:scores:all", WorkflowStep.CacheStatus.MISS, cacheMs);
        cacheMissCounter.increment();

        // Select API source
        String source = selectApiSource(trace);

        // Fetch from API
        long apiStart = System.nanoTime();
        List<Match> matches;
        try {
            matches = footballDataService.getLiveMatches();
            long apiMs = (System.nanoTime() - apiStart) / 1_000_000;
            trace.emitExternalApi(source, "Fetched " + matches.size() + " live matches", 200, apiMs);
        } catch (Exception e) {
            long apiMs = (System.nanoTime() - apiStart) / 1_000_000;
            trace.emitExternalApi(source, "Error: " + e.getMessage(), 500, apiMs);
            trace.emitError("API Fetch", e.getMessage(), apiMs);
            return Collections.emptyList();
        }

        // Cache result
        long writeStart = System.nanoTime();
        try {
            redisTemplate.opsForValue().set("live:scores:all", matches, 30, TimeUnit.SECONDS);
            long writeMs = (System.nanoTime() - writeStart) / 1_000_000;
            trace.emitCacheWrite("live:scores:all", "30s", writeMs);
        } catch (Exception e) {
            log.warn("Failed to cache live scores", e);
        }

        return matches;
    }

    public JsonNode getStandings(String leagueCode, WorkflowTracer.Trace trace) {
        String cacheKey = "standings:" + leagueCode;

        long cacheStart = System.nanoTime();
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        long cacheMs = (System.nanoTime() - cacheStart) / 1_000_000;

        if (cached != null) {
            trace.emitCacheCheck(cacheKey, WorkflowStep.CacheStatus.HIT, cacheMs);
            cacheHitCounter.increment();
            try {
                if (cached instanceof String) {
                    return objectMapper.readTree((String) cached);
                }
                return objectMapper.valueToTree(cached);
            } catch (Exception e) {
                log.warn("Failed to convert cached standings", e);
            }
        }
        trace.emitCacheCheck(cacheKey, WorkflowStep.CacheStatus.MISS, cacheMs);
        cacheMissCounter.increment();

        // Check rate limit
        int remaining = footballDataService.getRateLimiter().getRemainingQuota();
        trace.emitRateLimit(remaining, 10);

        long apiStart = System.nanoTime();
        JsonNode standings;
        try {
            standings = footballDataService.getStandings(leagueCode);
            long apiMs = (System.nanoTime() - apiStart) / 1_000_000;
            trace.emitExternalApi("Football-Data.org", "GET standings for " + leagueCode, 200, apiMs);
        } catch (Exception e) {
            long apiMs = (System.nanoTime() - apiStart) / 1_000_000;
            trace.emitError("Football-Data.org", e.getMessage(), apiMs);
            return objectMapper.createObjectNode();
        }

        // Cache only non-empty responses
        if (standings.has("standings")) {
            long writeStart = System.nanoTime();
            try {
                redisTemplate.opsForValue().set(cacheKey, standings.toString(), 5, TimeUnit.MINUTES);
                long writeMs = (System.nanoTime() - writeStart) / 1_000_000;
                trace.emitCacheWrite(cacheKey, "5min", writeMs);
            } catch (Exception e) {
                log.warn("Failed to cache standings", e);
            }
        }

        return standings;
    }

    public JsonNode getFixtures(String leagueCode, WorkflowTracer.Trace trace) {
        String cacheKey = "fixtures:" + leagueCode;

        long cacheStart = System.nanoTime();
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        long cacheMs = (System.nanoTime() - cacheStart) / 1_000_000;

        if (cached != null) {
            trace.emitCacheCheck(cacheKey, WorkflowStep.CacheStatus.HIT, cacheMs);
            cacheHitCounter.increment();
            try {
                if (cached instanceof String) {
                    return objectMapper.readTree((String) cached);
                }
                return objectMapper.valueToTree(cached);
            } catch (Exception e) {
                log.warn("Failed to convert cached fixtures", e);
            }
        }
        trace.emitCacheCheck(cacheKey, WorkflowStep.CacheStatus.MISS, cacheMs);
        cacheMissCounter.increment();

        long apiStart = System.nanoTime();
        JsonNode fixtures;
        try {
            fixtures = footballDataService.getFixtures(leagueCode);
            long apiMs = (System.nanoTime() - apiStart) / 1_000_000;
            trace.emitExternalApi("Football-Data.org", "GET fixtures for " + leagueCode, 200, apiMs);
        } catch (Exception e) {
            long apiMs = (System.nanoTime() - apiStart) / 1_000_000;
            trace.emitError("Football-Data.org", e.getMessage(), apiMs);
            return objectMapper.createObjectNode();
        }

        // Cache only non-empty responses
        if (fixtures.has("matches")) {
            long writeStart = System.nanoTime();
            try {
                redisTemplate.opsForValue().set(cacheKey, fixtures.toString(), 1, TimeUnit.HOURS);
                long writeMs = (System.nanoTime() - writeStart) / 1_000_000;
                trace.emitCacheWrite(cacheKey, "1hr", writeMs);
            } catch (Exception e) {
                log.warn("Failed to cache fixtures", e);
            }
        }

        return fixtures;
    }

    public List<Match> getTodaysMatches(WorkflowTracer.Trace trace) {
        String cacheKey = "matches:today";

        long cacheStart = System.nanoTime();
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        long cacheMs = (System.nanoTime() - cacheStart) / 1_000_000;

        if (cached != null) {
            trace.emitCacheCheck(cacheKey, WorkflowStep.CacheStatus.HIT, cacheMs);
            cacheHitCounter.increment();
            try {
                String json = objectMapper.writeValueAsString(cached);
                return Arrays.asList(objectMapper.readValue(json, Match[].class));
            } catch (Exception e) {
                log.warn("Failed to deserialize cached today's matches", e);
            }
        }
        trace.emitCacheCheck(cacheKey, WorkflowStep.CacheStatus.MISS, cacheMs);
        cacheMissCounter.increment();

        long apiStart = System.nanoTime();
        List<Match> matches;
        try {
            matches = footballDataService.getTodaysMatches();
            long apiMs = (System.nanoTime() - apiStart) / 1_000_000;
            trace.emitExternalApi("Football-Data.org", "GET today's matches: " + matches.size() + " found", 200, apiMs);
        } catch (Exception e) {
            long apiMs = (System.nanoTime() - apiStart) / 1_000_000;
            trace.emitError("Football-Data.org", e.getMessage(), apiMs);
            return Collections.emptyList();
        }

        long writeStart = System.nanoTime();
        try {
            redisTemplate.opsForValue().set(cacheKey, matches, 2, TimeUnit.MINUTES);
            long writeMs = (System.nanoTime() - writeStart) / 1_000_000;
            trace.emitCacheWrite(cacheKey, "2min", writeMs);
        } catch (Exception e) {
            log.warn("Failed to cache today's matches", e);
        }

        return matches;
    }

    private String selectApiSource(WorkflowTracer.Trace trace) {
        CircuitBreaker.State fdState = footballDataService.getMatchCircuitBreaker().getState();
        int remaining = footballDataService.getRateLimiter().getRemainingQuota();

        String source;
        String reason;

        if (fdState == CircuitBreaker.State.OPEN) {
            source = "Football-Data.org (degraded)";
            reason = "Circuit breaker OPEN, waiting for recovery";
            trace.emitCircuitBreaker("OPEN", footballDataService.getMatchCircuitBreaker().getFailureCount());
        } else if (remaining <= 1) {
            source = "Football-Data.org (throttled)";
            reason = "Rate limit nearly exhausted: " + remaining + "/10";
        } else {
            source = "Football-Data.org";
            reason = "Primary source, quota: " + remaining + "/10";
        }

        trace.emitApiSourceSelect(source, reason);
        trace.emitApiQuotaCheck("Football-Data.org",
                10 - remaining, 10);

        return source;
    }
}
