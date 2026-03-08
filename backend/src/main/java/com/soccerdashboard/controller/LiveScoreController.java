package com.soccerdashboard.controller;

import com.soccerdashboard.model.Match;
import com.soccerdashboard.service.LiveScoreAggregator;
import com.soccerdashboard.workflow.WorkflowTracer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/live")
public class LiveScoreController {

    private final LiveScoreAggregator liveScoreAggregator;
    private final WorkflowTracer workflowTracer;

    public LiveScoreController(LiveScoreAggregator liveScoreAggregator, WorkflowTracer workflowTracer) {
        this.liveScoreAggregator = liveScoreAggregator;
        this.workflowTracer = workflowTracer;
    }

    @GetMapping
    public ResponseEntity<List<Match>> getLiveMatches() {
        WorkflowTracer.Trace trace = workflowTracer.startUserAction("Get live matches");
        trace.emitApiGateway("GET /api/live");
        List<Match> matches = liveScoreAggregator.getLiveMatches(trace);
        trace.emitResponse(200, matches.size());
        return ResponseEntity.ok(matches);
    }

    @GetMapping("/today")
    public ResponseEntity<List<Match>> getTodaysMatches() {
        WorkflowTracer.Trace trace = workflowTracer.startUserAction("Get today's matches");
        trace.emitApiGateway("GET /api/live/today");
        List<Match> matches = liveScoreAggregator.getTodaysMatches(trace);
        trace.emitResponse(200, matches.size());
        return ResponseEntity.ok(matches);
    }
}
