package com.soccerdashboard.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.soccerdashboard.service.InsightService;
import com.soccerdashboard.service.LiveScoreAggregator;
import com.soccerdashboard.workflow.WorkflowTracer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/insights")
public class InsightController {

    private final InsightService insightService;
    private final LiveScoreAggregator liveScoreAggregator;
    private final WorkflowTracer workflowTracer;

    public InsightController(InsightService insightService,
                             LiveScoreAggregator liveScoreAggregator,
                             WorkflowTracer workflowTracer) {
        this.insightService = insightService;
        this.liveScoreAggregator = liveScoreAggregator;
        this.workflowTracer = workflowTracer;
    }

    @GetMapping("/{leagueCode}")
    public ResponseEntity<Map<String, Object>> getLeagueInsight(@PathVariable String leagueCode) {
        WorkflowTracer.Trace trace = workflowTracer.startUserAction("AI Insight: " + leagueCode);
        trace.emitApiGateway("GET /api/insights/" + leagueCode);

        // Get standings data first
        JsonNode standings = liveScoreAggregator.getStandings(leagueCode, trace);

        // Generate insight
        Map<String, Object> insight = insightService.getLeagueInsight(leagueCode, standings, trace);
        trace.emitResponse(200, 1);

        return ResponseEntity.ok(insight);
    }
}
