package com.soccerdashboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.soccerdashboard.workflow.WorkflowStep;
import com.soccerdashboard.workflow.WorkflowTracer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    private final FootballDataService footballDataService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    private final WorkflowTracer workflowTracer;
    private final Counter cacheHitCounter;
    private final Counter cacheMissCounter;

    // Cache teams in memory to avoid hammering the API
    private List<Map<String, Object>> cachedTeams = null;
    private long cacheTimestamp = 0;
    private static final long TEAM_CACHE_TTL = 30 * 60 * 1000; // 30 minutes

    public SearchService(FootballDataService footballDataService,
                         RedisTemplate<String, Object> redisTemplate,
                         ObjectMapper objectMapper,
                         WorkflowTracer workflowTracer,
                         MeterRegistry meterRegistry) {
        this.footballDataService = footballDataService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.workflowTracer = workflowTracer;
        this.cacheHitCounter = Counter.builder("cache.operations")
                .tag("operation", "hit").tag("key_pattern", "search")
                .register(meterRegistry);
        this.cacheMissCounter = Counter.builder("cache.operations")
                .tag("operation", "miss").tag("key_pattern", "search")
                .register(meterRegistry);
    }

    public Map<String, Object> search(String query) {
        WorkflowTracer.Trace trace = workflowTracer.startUserAction("Search: " + query);
        trace.emitApiGateway("GET /api/search?q=" + query);

        String queryLower = query.toLowerCase();

        // Search leagues (local, no API call)
        List<Map<String, Object>> matchingLeagues = footballDataService.getLeagues().stream()
                .filter(l -> l.get("name").toString().toLowerCase().contains(queryLower)
                        || l.get("code").toString().toLowerCase().contains(queryLower))
                .collect(Collectors.toList());

        // Search teams
        List<Map<String, Object>> allTeams = getAllTeams(trace);
        List<Map<String, Object>> matchingTeams = allTeams.stream()
                .filter(t -> {
                    String name = String.valueOf(t.getOrDefault("name", "")).toLowerCase();
                    String shortName = String.valueOf(t.getOrDefault("shortName", "")).toLowerCase();
                    String tla = String.valueOf(t.getOrDefault("tla", "")).toLowerCase();
                    return name.contains(queryLower) || shortName.contains(queryLower) || tla.contains(queryLower);
                })
                .limit(20)
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", query);
        result.put("leagues", matchingLeagues);
        result.put("teams", matchingTeams);

        trace.emitResponse(200, matchingLeagues.size() + matchingTeams.size());
        return result;
    }

    private synchronized List<Map<String, Object>> getAllTeams(WorkflowTracer.Trace trace) {
        // Check in-memory cache
        if (cachedTeams != null && System.currentTimeMillis() - cacheTimestamp < TEAM_CACHE_TTL) {
            trace.emitCacheCheck("teams:all", WorkflowStep.CacheStatus.HIT, 0);
            cacheHitCounter.increment();
            return cachedTeams;
        }

        // Check Redis cache
        long cacheStart = System.nanoTime();
        Object cached = redisTemplate.opsForValue().get("search:all_teams");
        long cacheMs = (System.nanoTime() - cacheStart) / 1_000_000;

        if (cached != null) {
            trace.emitCacheCheck("search:all_teams", WorkflowStep.CacheStatus.HIT, cacheMs);
            cacheHitCounter.increment();
            try {
                String json = cached instanceof String ? (String) cached : objectMapper.writeValueAsString(cached);
                List<Map<String, Object>> teams = objectMapper.readValue(json,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class));
                cachedTeams = teams;
                cacheTimestamp = System.currentTimeMillis();
                return teams;
            } catch (Exception e) {
                log.warn("Failed to deserialize cached teams", e);
            }
        }
        trace.emitCacheCheck("search:all_teams", WorkflowStep.CacheStatus.MISS, cacheMs);
        cacheMissCounter.increment();

        // Fetch from top 3 leagues to build a good team database without burning quota
        List<Map<String, Object>> teams = new ArrayList<>();
        String[] topLeagues = {"PL", "PD", "SA"};

        for (String league : topLeagues) {
            long apiStart = System.nanoTime();
            try {
                JsonNode data = footballDataService.getCompetitionTeams(league);
                long apiMs = (System.nanoTime() - apiStart) / 1_000_000;

                if (data.has("teams")) {
                    for (JsonNode team : data.get("teams")) {
                        Map<String, Object> t = new LinkedHashMap<>();
                        t.put("id", team.has("id") ? team.get("id").asInt() : 0);
                        t.put("name", team.has("name") ? team.get("name").asText("") : "");
                        t.put("shortName", team.has("shortName") ? team.get("shortName").asText("") : "");
                        t.put("tla", team.has("tla") ? team.get("tla").asText("") : "");
                        t.put("crest", team.has("crest") ? team.get("crest").asText("") : "");
                        t.put("league", league);
                        if (team.has("area") && team.get("area").has("name")) {
                            t.put("area", team.get("area").get("name").asText(""));
                        }
                        teams.add(t);
                    }
                    trace.emitExternalApi("Football-Data.org", "GET teams for " + league + ": " + data.get("teams").size(), 200, apiMs);
                }
            } catch (Exception e) {
                long apiMs = (System.nanoTime() - apiStart) / 1_000_000;
                log.warn("Failed to fetch teams for {}: {}", league, e.getMessage());
                trace.emitError("Football-Data.org", "Teams " + league + ": " + e.getMessage(), apiMs);
            }
        }

        // Cache in Redis for 30 min
        if (!teams.isEmpty()) {
            try {
                String json = objectMapper.writeValueAsString(teams);
                redisTemplate.opsForValue().set("search:all_teams", json, 30, TimeUnit.MINUTES);
                trace.emitCacheWrite("search:all_teams", "30min", 0);
            } catch (Exception e) {
                log.warn("Failed to cache teams", e);
            }
        }

        cachedTeams = teams;
        cacheTimestamp = System.currentTimeMillis();
        return teams;
    }
}
