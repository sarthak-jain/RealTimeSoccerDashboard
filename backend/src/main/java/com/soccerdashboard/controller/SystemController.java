package com.soccerdashboard.controller;

import com.soccerdashboard.service.DemoService;
import com.soccerdashboard.service.FootballDataService;
import com.soccerdashboard.service.PollingScheduler;
import com.soccerdashboard.service.WebSocketBroadcaster;
import com.soccerdashboard.workflow.WorkflowEmitter;
import com.soccerdashboard.workflow.WorkflowTracer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class SystemController {

    private final FootballDataService footballDataService;
    private final WebSocketBroadcaster webSocketBroadcaster;
    private final WorkflowEmitter workflowEmitter;
    private final WorkflowTracer workflowTracer;
    private final PollingScheduler pollingScheduler;
    private final DemoService demoService;

    public SystemController(FootballDataService footballDataService,
                            WebSocketBroadcaster webSocketBroadcaster,
                            WorkflowEmitter workflowEmitter,
                            WorkflowTracer workflowTracer,
                            PollingScheduler pollingScheduler,
                            DemoService demoService) {
        this.footballDataService = footballDataService;
        this.webSocketBroadcaster = webSocketBroadcaster;
        this.workflowEmitter = workflowEmitter;
        this.workflowTracer = workflowTracer;
        this.pollingScheduler = pollingScheduler;
        this.demoService = demoService;
    }

    @GetMapping("/system/status")
    public ResponseEntity<Map<String, Object>> getSystemStatus() {
        Map<String, Object> status = new LinkedHashMap<>();

        status.put("pollCycleNumber", workflowTracer.getCurrentCycleNumber());
        status.put("wsClients", webSocketBroadcaster.getSessionCount());
        status.put("sseClients", workflowEmitter.getActiveClientCount());
        status.put("demoMode", pollingScheduler.isDemoMode());

        Map<String, Object> apiStatus = new LinkedHashMap<>();
        apiStatus.put("remainingQuota", footballDataService.getRateLimiter().getRemainingQuota());
        apiStatus.put("maxQuota", footballDataService.getRateLimiter().getMaxRequests());
        apiStatus.put("matchCircuitBreaker", footballDataService.getMatchCircuitBreaker().getState().toString());
        apiStatus.put("jsonCircuitBreaker", footballDataService.getJsonCircuitBreaker().getState().toString());
        status.put("footballData", apiStatus);

        Map<String, Object> demoStatus = new LinkedHashMap<>();
        demoStatus.put("totalFrames", demoService.getTotalFrames());
        demoStatus.put("currentFrame", demoService.getCurrentFrame());
        status.put("demo", demoStatus);

        return ResponseEntity.ok(status);
    }

    @PostMapping("/system/reset-circuits")
    public ResponseEntity<Map<String, Object>> resetCircuitBreakers() {
        footballDataService.getMatchCircuitBreaker().reset();
        footballDataService.getJsonCircuitBreaker().reset();
        return ResponseEntity.ok(Map.of("status", "all circuit breakers reset"));
    }

    @PostMapping("/demo/toggle")
    public ResponseEntity<Map<String, Object>> toggleDemoMode() {
        boolean newState = !pollingScheduler.isDemoMode();
        pollingScheduler.setDemoMode(newState);

        if (newState) {
            demoService.resetDemo();
            WorkflowTracer.Trace trace = workflowTracer.startSystemEvent("Demo Mode Enabled");
            trace.emitApiGateway("POST /api/demo/toggle");
            trace.emitResponse(200, 0);
        } else {
            WorkflowTracer.Trace trace = workflowTracer.startSystemEvent("Demo Mode Disabled");
            trace.emitApiGateway("POST /api/demo/toggle");
            trace.emitResponse(200, 0);
        }

        return ResponseEntity.ok(Map.of("demoMode", newState));
    }
}
