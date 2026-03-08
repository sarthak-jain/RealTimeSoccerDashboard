package com.soccerdashboard.controller;

import com.soccerdashboard.service.SearchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> search(@RequestParam String q) {
        return ResponseEntity.ok(searchService.search(q));
    }
}
