package com.soccerdashboard.config;

import com.soccerdashboard.resilience.CircuitBreaker;
import com.soccerdashboard.resilience.RateLimiter;
import com.soccerdashboard.service.FootballDataService;
import com.soccerdashboard.service.WebSocketBroadcaster;
import com.soccerdashboard.workflow.WorkflowEmitter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DatadogMetricsConfig {

    public DatadogMetricsConfig(MeterRegistry registry,
                                 FootballDataService footballDataService,
                                 WebSocketBroadcaster webSocketBroadcaster,
                                 WorkflowEmitter workflowEmitter) {

        // Circuit breaker state gauges (0=CLOSED, 1=HALF_OPEN, 2=OPEN)
        registerCircuitBreaker(registry, "matches", footballDataService.getMatchCircuitBreaker());
        registerCircuitBreaker(registry, "json", footballDataService.getJsonCircuitBreaker());

        // Rate limiter gauges
        RateLimiter rateLimiter = footballDataService.getRateLimiter();
        Gauge.builder("rate_limiter.remaining_quota", rateLimiter, RateLimiter::getRemainingQuota)
                .tag("name", rateLimiter.getName())
                .register(registry);
        Gauge.builder("rate_limiter.used_quota", rateLimiter, rl -> rl.getMaxRequests() - rl.getRemainingQuota())
                .tag("name", rateLimiter.getName())
                .register(registry);

        // WebSocket active sessions
        Gauge.builder("websocket.active_sessions", webSocketBroadcaster, WebSocketBroadcaster::getSessionCount)
                .register(registry);

        // SSE active clients
        Gauge.builder("sse.active_clients", workflowEmitter, WorkflowEmitter::getActiveClientCount)
                .register(registry);
    }

    private <T> void registerCircuitBreaker(MeterRegistry registry, String name, CircuitBreaker<T> cb) {
        Gauge.builder("circuit_breaker.state", cb, c -> switch (c.getState()) {
                    case CLOSED -> 0;
                    case HALF_OPEN -> 1;
                    case OPEN -> 2;
                })
                .tag("name", name)
                .register(registry);

        Gauge.builder("circuit_breaker.failure_count", cb, c -> (double) c.getFailureCount())
                .tag("name", name)
                .register(registry);
    }
}
