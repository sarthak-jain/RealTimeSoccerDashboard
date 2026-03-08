package com.soccerdashboard.controller;

import com.soccerdashboard.service.NarratorService;
import com.soccerdashboard.workflow.WorkflowEmitter;
import com.soccerdashboard.workflow.WorkflowTracer;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/workflow")
public class WorkflowController {

    private final WorkflowEmitter workflowEmitter;
    private final NarratorService narratorService;
    private final WorkflowTracer workflowTracer;

    public WorkflowController(WorkflowEmitter workflowEmitter, NarratorService narratorService,
                               WorkflowTracer workflowTracer) {
        this.workflowEmitter = workflowEmitter;
        this.narratorService = narratorService;
        this.workflowTracer = workflowTracer;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamWorkflow() {
        return workflowEmitter.createEmitter();
    }

    @PostMapping("/narrate")
    public ResponseEntity<Map<String, Object>> narrate(@RequestBody List<Map<String, Object>> events) {
        WorkflowTracer.Trace trace = workflowTracer.startSystemEvent("Panel Narrator");
        trace.emitApiGateway("POST /api/workflow/narrate");
        Map<String, Object> result = narratorService.narrate(events, trace);
        trace.emitResponse(200, 1);
        return ResponseEntity.ok(result);
    }
}
