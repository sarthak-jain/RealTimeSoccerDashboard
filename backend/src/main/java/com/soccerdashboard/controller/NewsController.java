package com.soccerdashboard.controller;

import com.soccerdashboard.service.NewsService;
import com.soccerdashboard.workflow.WorkflowTracer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/news")
public class NewsController {

    private final NewsService newsService;
    private final WorkflowTracer workflowTracer;

    public NewsController(NewsService newsService, WorkflowTracer workflowTracer) {
        this.newsService = newsService;
        this.workflowTracer = workflowTracer;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getNews() {
        WorkflowTracer.Trace trace = workflowTracer.startUserAction("Get soccer news");
        trace.emitApiGateway("GET /api/news");
        Map<String, Object> news = newsService.getNews(trace);
        trace.emitResponse(200, ((java.util.List<?>) news.getOrDefault("articles", java.util.Collections.emptyList())).size());
        return ResponseEntity.ok(news);
    }

    @GetMapping("/brief")
    public ResponseEntity<Map<String, Object>> getNewsBrief() {
        WorkflowTracer.Trace trace = workflowTracer.startUserAction("AI News Brief");
        trace.emitApiGateway("GET /api/news/brief");
        Map<String, Object> brief = newsService.getNewsBrief(trace);
        trace.emitResponse(200, 1);
        return ResponseEntity.ok(brief);
    }
}
