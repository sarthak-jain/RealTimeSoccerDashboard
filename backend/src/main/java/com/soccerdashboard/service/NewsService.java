package com.soccerdashboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soccerdashboard.workflow.WorkflowStep;
import com.soccerdashboard.workflow.WorkflowTracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class NewsService {

    private static final Logger log = LoggerFactory.getLogger(NewsService.class);

    private final String apiKey;
    private final String claudeApiKey;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    public NewsService(
            @Value("${app.gnews.api-key:}") String apiKey,
            @Value("${app.claude.api-key:}") String claudeApiKey,
            ObjectMapper objectMapper,
            RedisTemplate<String, Object> redisTemplate) {
        this.apiKey = apiKey;
        this.claudeApiKey = claudeApiKey;
        this.restTemplate = new RestTemplate();
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
    }

    public Map<String, Object> getNews(WorkflowTracer.Trace trace) {
        String cacheKey = "news:soccer";

        // Check cache (15 min TTL)
        long cacheStart = System.nanoTime();
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        long cacheMs = (System.nanoTime() - cacheStart) / 1_000_000;

        if (cached != null) {
            trace.emitCacheCheck(cacheKey, WorkflowStep.CacheStatus.HIT, cacheMs);
            try {
                String json = cached instanceof String ? (String) cached : objectMapper.writeValueAsString(cached);
                return objectMapper.readValue(json,
                        objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
            } catch (Exception e) {
                log.warn("Failed to deserialize cached news", e);
            }
        }
        trace.emitCacheCheck(cacheKey, WorkflowStep.CacheStatus.MISS, cacheMs);

        if (apiKey == null || apiKey.isEmpty()) {
            trace.emitError("GNews", "No API key configured", 0);
            return Map.of("articles", Collections.emptyList(), "error", "GNEWS_API_KEY not set");
        }

        // Fetch from GNews
        long apiStart = System.nanoTime();
        try {
            URI uri = URI.create("https://gnews.io/api/v4/search?q=soccer%20OR%20%22premier%20league%22%20OR%20%22champions%20league%22%20OR%20%22la%20liga%22%20OR%20%22serie%20a%22&lang=en&max=10&sortby=publishedAt&apikey=" + apiKey);
            ResponseEntity<String> response = restTemplate.getForEntity(uri, String.class);
            long apiMs = (System.nanoTime() - apiStart) / 1_000_000;
            trace.emitExternalApi("GNews", "GET soccer news", 200, apiMs);

            JsonNode root = objectMapper.readTree(response.getBody());
            List<Map<String, Object>> articles = new ArrayList<>();

            if (root.has("articles")) {
                for (JsonNode article : root.get("articles")) {
                    Map<String, Object> a = new LinkedHashMap<>();
                    a.put("title", article.has("title") ? article.get("title").asText() : "");
                    a.put("description", article.has("description") ? article.get("description").asText() : "");
                    a.put("url", article.has("url") ? article.get("url").asText() : "");
                    a.put("image", article.has("image") ? article.get("image").asText() : null);
                    a.put("publishedAt", article.has("publishedAt") ? article.get("publishedAt").asText() : "");
                    if (article.has("source")) {
                        Map<String, String> source = new LinkedHashMap<>();
                        source.put("name", article.get("source").has("name") ? article.get("source").get("name").asText() : "");
                        a.put("source", source);
                    }
                    articles.add(a);
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("articles", articles);

            // Cache for 15 minutes
            try {
                String json = objectMapper.writeValueAsString(result);
                redisTemplate.opsForValue().set(cacheKey, json, 15, TimeUnit.MINUTES);
                trace.emitCacheWrite(cacheKey, "15min", 0);
            } catch (Exception e) {
                log.warn("Failed to cache news", e);
            }

            return result;
        } catch (Exception e) {
            long apiMs = (System.nanoTime() - apiStart) / 1_000_000;
            log.warn("Failed to fetch news: {}", e.getMessage());
            trace.emitError("GNews", e.getMessage(), apiMs);
            return Map.of("articles", Collections.emptyList());
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getNewsBrief(WorkflowTracer.Trace trace) {
        String cacheKey = "news:brief";

        // Check cache (30 min TTL)
        long cacheStart = System.nanoTime();
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        long cacheMs = (System.nanoTime() - cacheStart) / 1_000_000;

        if (cached != null) {
            trace.emitCacheCheck(cacheKey, WorkflowStep.CacheStatus.HIT, cacheMs);
            try {
                String json = cached instanceof String ? (String) cached : objectMapper.writeValueAsString(cached);
                Map<String, Object> cachedResult = objectMapper.readValue(json,
                        objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));

                String cachedAt = cachedResult.getOrDefault("generatedAt", "unknown").toString();
                String briefPreview = cachedResult.getOrDefault("brief", "").toString();
                briefPreview = briefPreview.length() > 120 ? briefPreview.substring(0, 120) + "..." : briefPreview;

                com.soccerdashboard.workflow.WorkflowStep llmCached = new com.soccerdashboard.workflow.WorkflowStep();
                llmCached.setTraceId(trace.getTraceId());
                llmCached.setStepNumber(trace.nextStep());
                llmCached.setType(com.soccerdashboard.workflow.WorkflowStep.StepType.LLM_INFERENCE);
                llmCached.setName("LLM Result (cached)");
                llmCached.setDetail("Serving cached news brief from claude-haiku-4-5");
                llmCached.setDurationMs(0);
                llmCached.withMeta("Model", "claude-haiku-4-5");
                llmCached.withMeta("Status", "Served from cache");
                llmCached.withMeta("Generated", cachedAt);
                llmCached.withMeta("Preview", briefPreview);
                trace.emit(llmCached);

                return cachedResult;
            } catch (Exception e) {
                log.warn("Failed to deserialize cached brief", e);
            }
        }
        trace.emitCacheCheck(cacheKey, WorkflowStep.CacheStatus.MISS, cacheMs);

        // Fetch articles first
        Map<String, Object> newsData = getNews(trace);
        List<Map<String, Object>> articles = (List<Map<String, Object>>) newsData.getOrDefault("articles", Collections.emptyList());

        if (articles.isEmpty()) {
            return Map.of("brief", "", "error", "No articles to summarize");
        }

        if (claudeApiKey == null || claudeApiKey.isEmpty()) {
            trace.emitError("Claude API", "No API key configured", 0);
            return Map.of("brief", "", "error", "CLAUDE_API_KEY not set");
        }

        // Build article summary for the prompt
        StringBuilder articlesSummary = new StringBuilder();
        for (int i = 0; i < articles.size(); i++) {
            Map<String, Object> a = articles.get(i);
            articlesSummary.append(String.format("%d. %s\n   %s\n\n",
                    i + 1,
                    a.getOrDefault("title", ""),
                    a.getOrDefault("description", "")));
        }

        // Emit prompt build step
        com.soccerdashboard.workflow.WorkflowStep promptStep = new com.soccerdashboard.workflow.WorkflowStep();
        promptStep.setTraceId(trace.getTraceId());
        promptStep.setStepNumber(trace.nextStep());
        promptStep.setType(com.soccerdashboard.workflow.WorkflowStep.StepType.LLM_INFERENCE);
        promptStep.setName("Prompt Build");
        promptStep.setDetail(String.format("Building news digest prompt from %d articles", articles.size()));
        promptStep.setDurationMs(0);
        promptStep.withMeta("Articles", String.valueOf(articles.size()));
        promptStep.withMeta("Task", "Summarize into Today's Soccer Brief");
        trace.emit(promptStep);

        String prompt = "You are a football/soccer journalist writing a daily briefing. " +
                "Summarize these " + articles.size() + " news articles into a concise \"Today's Soccer Brief\" — " +
                "3-4 punchy sentences that capture the most important stories. " +
                "Write in a confident, engaging journalist tone. No bullet points or headers, just a flowing paragraph.\n\n" +
                "Articles:\n" + articlesSummary;

        // Call Claude API
        long apiStart = System.nanoTime();
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-api-key", claudeApiKey);
            headers.set("anthropic-version", "2023-06-01");

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", "claude-haiku-4-5-20251001");
            body.put("max_tokens", 300);
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

            String brief = "";
            if (responseJson.has("content") && responseJson.get("content").isArray()) {
                for (JsonNode block : responseJson.get("content")) {
                    if ("text".equals(block.path("type").asText())) {
                        brief = block.get("text").asText();
                        break;
                    }
                }
            }

            // Emit LLM inference step
            com.soccerdashboard.workflow.WorkflowStep llmStep = com.soccerdashboard.workflow.WorkflowStep.llmInference(
                    trace.getTraceId(), trace.nextStep(), "claude-haiku-4-5",
                    "News brief digest", inputTokens, outputTokens, apiMs);
            llmStep.withMeta("Endpoint", "api.anthropic.com/v1/messages");
            llmStep.withMeta("Max tokens", "300");
            String briefPreview = brief.length() > 120 ? brief.substring(0, 120) + "..." : brief;
            llmStep.withMeta("Response preview", briefPreview);
            trace.emit(llmStep);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("brief", brief);
            result.put("articleCount", articles.size());
            result.put("model", "claude-haiku-4-5");
            result.put("generatedAt", new Date().toInstant().toString());

            // Cache for 30 minutes
            try {
                String json = objectMapper.writeValueAsString(result);
                redisTemplate.opsForValue().set(cacheKey, json, 30, TimeUnit.MINUTES);
                trace.emitCacheWrite(cacheKey, "30min", 0);
            } catch (Exception e) {
                log.warn("Failed to cache news brief", e);
            }

            return result;
        } catch (Exception e) {
            long apiMs = (System.nanoTime() - apiStart) / 1_000_000;
            log.warn("Failed to generate news brief: {}", e.getMessage());
            trace.emitError("Claude API", e.getMessage(), apiMs);
            return Map.of("brief", "", "error", "Failed to generate brief");
        }
    }
}
