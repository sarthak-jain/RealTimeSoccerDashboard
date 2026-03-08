package com.soccerdashboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soccerdashboard.workflow.WorkflowStep;
import com.soccerdashboard.workflow.WorkflowTracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class InsightService {

    private static final Logger log = LoggerFactory.getLogger(InsightService.class);

    private final String apiKey;
    private final ObjectMapper objectMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final RestTemplate restTemplate;

    public InsightService(
            @Value("${app.claude.api-key:}") String apiKey,
            ObjectMapper objectMapper,
            RedisTemplate<String, Object> redisTemplate) {
        this.apiKey = apiKey;
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
        this.restTemplate = new RestTemplate();
    }

    public Map<String, Object> getLeagueInsight(String leagueCode, JsonNode standings, WorkflowTracer.Trace trace) {
        String cacheKey = "insight:" + leagueCode;

        // Check cache (1hr TTL)
        long cacheStart = System.nanoTime();
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        long cacheMs = (System.nanoTime() - cacheStart) / 1_000_000;

        if (cached != null) {
            trace.emitCacheCheck(cacheKey, WorkflowStep.CacheStatus.HIT, cacheMs);
            try {
                String json = cached instanceof String ? (String) cached : objectMapper.writeValueAsString(cached);
                Map<String, Object> cachedResult = objectMapper.readValue(json,
                        objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));

                // Emit cached LLM details so the panel shows what was originally generated
                String cachedModel = cachedResult.getOrDefault("model", "claude-haiku-4-5").toString();
                String cachedAt = cachedResult.getOrDefault("generatedAt", "unknown").toString();
                String cachedInsight = cachedResult.getOrDefault("insight", "").toString();
                String insightPreview = cachedInsight.length() > 120
                        ? cachedInsight.substring(0, 120) + "..."
                        : cachedInsight;

                WorkflowStep llmCached = new WorkflowStep();
                llmCached.setTraceId(trace.getTraceId());
                llmCached.setStepNumber(trace.nextStep());
                llmCached.setType(WorkflowStep.StepType.LLM_INFERENCE);
                llmCached.setName("LLM Result (cached)");
                llmCached.setDetail("Serving cached analysis from " + cachedModel);
                llmCached.setDurationMs(0);
                llmCached.withMeta("Model", cachedModel);
                llmCached.withMeta("Status", "Served from cache");
                llmCached.withMeta("Generated", cachedAt);
                llmCached.withMeta("Preview", insightPreview);
                trace.emit(llmCached);

                return cachedResult;
            } catch (Exception e) {
                log.warn("Failed to deserialize cached insight", e);
            }
        }
        trace.emitCacheCheck(cacheKey, WorkflowStep.CacheStatus.MISS, cacheMs);

        if (apiKey == null || apiKey.isEmpty()) {
            trace.emitError("Claude API", "No API key configured", 0);
            return Map.of("insight", "", "error", "CLAUDE_API_KEY not set");
        }

        // Build standings summary for the prompt
        String standingsSummary = buildStandingsSummary(standings);
        if (standingsSummary.isEmpty()) {
            return Map.of("insight", "", "error", "No standings data to analyze");
        }

        String leagueName = "";
        if (standings.has("competition") && standings.get("competition").has("name")) {
            leagueName = standings.get("competition").get("name").asText();
        }

        // Count teams in standings for metadata
        int teamCount = 0;
        if (standings.has("standings") && standings.get("standings").isArray()
                && !standings.get("standings").isEmpty()) {
            JsonNode table = standings.get("standings").get(0).get("table");
            if (table != null && table.isArray()) teamCount = table.size();
        }

        // Emit prompt construction step
        WorkflowStep promptStep = new WorkflowStep();
        promptStep.setTraceId(trace.getTraceId());
        promptStep.setStepNumber(trace.nextStep());
        promptStep.setType(WorkflowStep.StepType.LLM_INFERENCE);
        promptStep.setName("Prompt Build");
        promptStep.setDetail(String.format("Building analysis prompt for %s (%d teams)", leagueName, teamCount));
        promptStep.setDurationMs(0);
        promptStep.withMeta("League", leagueName);
        promptStep.withMeta("Teams", String.valueOf(teamCount));
        promptStep.withMeta("Topics", "Title race, Relegation, Surprises, Storylines");
        promptStep.withMeta("Standings rows", String.valueOf(standingsSummary.split("\n").length - 2));
        trace.emit(promptStep);

        String prompt = "You are a football/soccer analyst. Analyze the current " + leagueName +
                " standings and provide a brief, insightful analysis (3-4 short paragraphs max). Cover:\n" +
                "- Title race situation\n" +
                "- Any relegation battles\n" +
                "- Surprising performers (over/under-achieving teams)\n" +
                "- Key storylines\n\n" +
                "Be concise, punchy, and use a confident analyst tone. No markdown headers, just flowing paragraphs.\n\n" +
                "Current standings:\n" + standingsSummary;

        // Call Claude API
        long apiStart = System.nanoTime();
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-api-key", apiKey);
            headers.set("anthropic-version", "2023-06-01");

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", "claude-haiku-4-5-20251001");
            body.put("max_tokens", 500);
            body.put("messages", List.of(Map.of("role", "user", "content", prompt)));

            String requestJson = objectMapper.writeValueAsString(body);
            HttpEntity<String> request = new HttpEntity<>(requestJson, headers);

            restTemplate.getMessageConverters().forEach(converter -> {
                if (converter instanceof org.springframework.http.converter.StringHttpMessageConverter) {
                    ((org.springframework.http.converter.StringHttpMessageConverter) converter)
                            .setDefaultCharset(java.nio.charset.StandardCharsets.UTF_8);
                }
            });
            String responseBody = restTemplate.postForObject(
                    "https://api.anthropic.com/v1/messages", request, String.class);

            long apiMs = (System.nanoTime() - apiStart) / 1_000_000;

            JsonNode responseJson = objectMapper.readTree(responseBody);

            // Extract token usage
            int inputTokens = 0;
            int outputTokens = 0;
            if (responseJson.has("usage")) {
                inputTokens = responseJson.get("usage").has("input_tokens") ? responseJson.get("usage").get("input_tokens").asInt() : 0;
                outputTokens = responseJson.get("usage").has("output_tokens") ? responseJson.get("usage").get("output_tokens").asInt() : 0;
            }

            String insight = "";
            if (responseJson.has("content") && responseJson.get("content").isArray()) {
                for (JsonNode block : responseJson.get("content")) {
                    if ("text".equals(block.get("type").asText())) {
                        insight = block.get("text").asText();
                        break;
                    }
                }
            }

            // Emit rich LLM inference step with full details
            WorkflowStep llmStep = WorkflowStep.llmInference(
                    trace.getTraceId(), trace.nextStep(), "claude-haiku-4-5",
                    "League analysis: " + leagueName + " (" + leagueCode + ")",
                    inputTokens, outputTokens, apiMs);
            llmStep.withMeta("Endpoint", "api.anthropic.com/v1/messages");
            llmStep.withMeta("Max tokens", "500");
            String insightPreview = insight.length() > 120 ? insight.substring(0, 120) + "..." : insight;
            llmStep.withMeta("Response preview", insightPreview);
            trace.emit(llmStep);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("leagueCode", leagueCode);
            result.put("leagueName", leagueName);
            result.put("insight", insight);
            result.put("model", "claude-haiku-4-5");
            result.put("generatedAt", new Date().toInstant().toString());

            // Cache for 1 hour
            try {
                String json = objectMapper.writeValueAsString(result);
                redisTemplate.opsForValue().set(cacheKey, json, 1, TimeUnit.HOURS);
                trace.emitCacheWrite(cacheKey, "1hr", 0);
            } catch (Exception e) {
                log.warn("Failed to cache insight", e);
            }

            return result;
        } catch (Exception e) {
            long apiMs = (System.nanoTime() - apiStart) / 1_000_000;
            log.warn("Failed to generate insight: {}", e.getMessage());
            trace.emitError("Claude API", e.getMessage(), apiMs);
            return Map.of("insight", "", "error", "Failed to generate insight");
        }
    }

    private String buildStandingsSummary(JsonNode standings) {
        if (!standings.has("standings")) return "";

        JsonNode standingsArr = standings.get("standings");
        if (!standingsArr.isArray() || standingsArr.isEmpty()) return "";

        JsonNode table = standingsArr.get(0).get("table");
        if (table == null || !table.isArray()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("Pos | Team | P | W | D | L | GF | GA | GD | Pts\n");
        sb.append("---|---|---|---|---|---|---|---|---|---\n");

        for (JsonNode row : table) {
            sb.append(row.get("position").asInt()).append(" | ");
            sb.append(row.get("team").get("name").asText()).append(" | ");
            sb.append(row.get("playedGames").asInt()).append(" | ");
            sb.append(row.get("won").asInt()).append(" | ");
            sb.append(row.get("draw").asInt()).append(" | ");
            sb.append(row.get("lost").asInt()).append(" | ");
            sb.append(row.get("goalsFor").asInt()).append(" | ");
            sb.append(row.get("goalsAgainst").asInt()).append(" | ");
            sb.append(row.get("goalDifference").asInt()).append(" | ");
            sb.append(row.get("points").asInt()).append("\n");
        }

        return sb.toString();
    }
}
