package com.soccerdashboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soccerdashboard.workflow.WorkflowStep;
import com.soccerdashboard.workflow.WorkflowTracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class NarratorService {

    private static final Logger log = LoggerFactory.getLogger(NarratorService.class);

    private static final long COOLDOWN_MS = 120_000; // 2 minutes between narrations

    private final String apiKey;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private volatile long lastNarrationTime = 0;
    private volatile String lastNarration = "";

    public NarratorService(
            @Value("${app.claude.api-key:}") String apiKey,
            ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.objectMapper = objectMapper;
        this.restTemplate = new RestTemplate();
    }

    public Map<String, Object> narrate(List<Map<String, Object>> events, WorkflowTracer.Trace trace) {
        if (apiKey == null || apiKey.isEmpty()) {
            trace.emitError("Claude API", "No API key configured", 0);
            return Map.of("narration", "", "error", "CLAUDE_API_KEY not set");
        }

        if (events == null || events.isEmpty()) {
            return Map.of("narration", "No recent events to narrate.");
        }

        // Cooldown — return last narration if called too soon
        long now = System.currentTimeMillis();
        if (now - lastNarrationTime < COOLDOWN_MS && !lastNarration.isEmpty()) {
            trace.emitCacheCheck("narrator:cooldown", WorkflowStep.CacheStatus.HIT, 0);
            return Map.of("narration", lastNarration, "cached", true);
        }

        // Build event summary for prompt
        StringBuilder eventSummary = new StringBuilder();
        for (int i = 0; i < events.size(); i++) {
            Map<String, Object> event = events.get(i);
            eventSummary.append(String.format("[%d] %s — %s — %s",
                    i + 1,
                    event.getOrDefault("type", "UNKNOWN"),
                    event.getOrDefault("name", ""),
                    event.getOrDefault("detail", "")));
            if (event.containsKey("durationMs")) {
                eventSummary.append(String.format(" (%sms)", event.get("durationMs")));
            }
            if (event.containsKey("metadata")) {
                eventSummary.append(" | ").append(event.get("metadata"));
            }
            eventSummary.append("\n");
        }

        WorkflowStep promptStep = new WorkflowStep();
        promptStep.setTraceId(trace.getTraceId());
        promptStep.setStepNumber(trace.nextStep());
        promptStep.setType(WorkflowStep.StepType.LLM_INFERENCE);
        promptStep.setName("Prompt Build");
        promptStep.setDetail(String.format("Building narrator prompt from %d events", events.size()));
        promptStep.setDurationMs(0);
        promptStep.withMeta("Events", String.valueOf(events.size()));
        trace.emit(promptStep);

        String prompt = "You are narrating a live system design panel for a real-time soccer dashboard. " +
                "The panel shows backend workflow events: API calls, cache hits/misses, rate limiting, " +
                "circuit breakers, WebSocket broadcasts, database operations, polling cycles, and LLM inferences.\n\n" +
                "Given these recent events, write 1-2 plain-English sentences explaining what the system just did " +
                "and why it matters. Be specific about the technical details but make it accessible. " +
                "Use present tense. Don't use bullet points or headers. Be concise and insightful.\n\n" +
                "Recent events:\n" + eventSummary;

        long apiStart = System.nanoTime();
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-api-key", apiKey);
            headers.set("anthropic-version", "2023-06-01");

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", "claude-haiku-4-5-20251001");
            body.put("max_tokens", 150);
            body.put("messages", List.of(Map.of("role", "user", "content", prompt)));

            String requestJson = objectMapper.writeValueAsString(body);
            HttpEntity<String> request = new HttpEntity<>(requestJson, headers);

            restTemplate.getMessageConverters().forEach(converter -> {
                if (converter instanceof org.springframework.http.converter.StringHttpMessageConverter) {
                    ((org.springframework.http.converter.StringHttpMessageConverter) converter)
                            .setDefaultCharset(StandardCharsets.UTF_8);
                }
            });
            String responseBody = restTemplate.postForObject(
                    "https://api.anthropic.com/v1/messages", request, String.class);

            long apiMs = (System.nanoTime() - apiStart) / 1_000_000;
            JsonNode responseJson = objectMapper.readTree(responseBody);

            int inputTokens = 0, outputTokens = 0;
            if (responseJson.has("usage")) {
                inputTokens = responseJson.path("usage").path("input_tokens").asInt(0);
                outputTokens = responseJson.path("usage").path("output_tokens").asInt(0);
            }

            String narration = "";
            if (responseJson.has("content") && responseJson.get("content").isArray()) {
                for (JsonNode block : responseJson.get("content")) {
                    if ("text".equals(block.path("type").asText())) {
                        narration = block.get("text").asText();
                        break;
                    }
                }
            }

            WorkflowStep llmStep = WorkflowStep.llmInference(
                    trace.getTraceId(), trace.nextStep(), "claude-haiku-4-5",
                    "Panel narration", inputTokens, outputTokens, apiMs);
            llmStep.withMeta("Endpoint", "api.anthropic.com/v1/messages");
            llmStep.withMeta("Max tokens", "150");
            trace.emit(llmStep);

            lastNarrationTime = System.currentTimeMillis();
            lastNarration = narration;

            return Map.of("narration", narration);
        } catch (Exception e) {
            long apiMs = (System.nanoTime() - apiStart) / 1_000_000;
            log.warn("Failed to generate narration: {}", e.getMessage());
            trace.emitError("Claude API", e.getMessage(), apiMs);
            return Map.of("narration", "", "error", "Failed to generate narration");
        }
    }
}
