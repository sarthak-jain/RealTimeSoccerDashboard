package com.soccerdashboard.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.soccerdashboard.service.FootballDataService;
import com.soccerdashboard.service.LiveScoreAggregator;
import com.soccerdashboard.workflow.WorkflowTracer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/leagues")
public class LeagueController {

    private final FootballDataService footballDataService;
    private final LiveScoreAggregator liveScoreAggregator;
    private final WorkflowTracer workflowTracer;

    public LeagueController(FootballDataService footballDataService,
                            LiveScoreAggregator liveScoreAggregator,
                            WorkflowTracer workflowTracer) {
        this.footballDataService = footballDataService;
        this.liveScoreAggregator = liveScoreAggregator;
        this.workflowTracer = workflowTracer;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getLeagues() {
        WorkflowTracer.Trace trace = workflowTracer.startUserAction("Get leagues");
        trace.emitApiGateway("GET /api/leagues");
        List<Map<String, Object>> leagues = footballDataService.getLeagues();
        trace.emitResponse(200, leagues.size());
        return ResponseEntity.ok(leagues);
    }

    @GetMapping("/{code}/standings")
    public ResponseEntity<?> getStandings(@PathVariable String code) {
        WorkflowTracer.Trace trace = workflowTracer.startUserAction("Get standings: " + code);
        trace.emitApiGateway("GET /api/leagues/" + code + "/standings");

        JsonNode standings = liveScoreAggregator.getStandings(code, trace);
        trace.emitResponse(200, 1);
        return ResponseEntity.ok(standings);
    }

    @GetMapping("/{code}/fixtures")
    public ResponseEntity<?> getFixtures(@PathVariable String code) {
        WorkflowTracer.Trace trace = workflowTracer.startUserAction("Get fixtures: " + code);
        trace.emitApiGateway("GET /api/leagues/" + code + "/fixtures");

        JsonNode fixtures = liveScoreAggregator.getFixtures(code, trace);
        trace.emitResponse(200, 1);
        return ResponseEntity.ok(fixtures);
    }
}
