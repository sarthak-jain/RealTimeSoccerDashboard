package com.soccerdashboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soccerdashboard.model.Match;
import com.soccerdashboard.resilience.CircuitBreaker;
import com.soccerdashboard.resilience.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
public class FootballDataService {

    private static final Logger log = LoggerFactory.getLogger(FootballDataService.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String baseUrl;
    private final RateLimiter rateLimiter;
    private final CircuitBreaker<List<Match>> matchCircuitBreaker;
    private final CircuitBreaker<JsonNode> jsonCircuitBreaker;
    private final Timer apiTimer;
    private final Counter rateLimitRejectionsCounter;

    // Football-Data.org league codes
    public static final Map<String, String> LEAGUE_CODES = new LinkedHashMap<>() {{
        put("PL", "Premier League");
        put("PD", "La Liga");
        put("SA", "Serie A");
        put("BL1", "Bundesliga");
        put("FL1", "Ligue 1");
        put("CL", "Champions League");
        put("ELC", "Championship");
        put("PPL", "Primeira Liga");
        put("DED", "Eredivisie");
        put("BSA", "Serie A (Brazil)");
        put("EC", "European Championship");
        put("WC", "World Cup");
    }};

    public FootballDataService(
            @Value("${app.football-data.api-key:}") String apiKey,
            @Value("${app.football-data.base-url:https://api.football-data.org/v4}") String baseUrl,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.objectMapper = objectMapper;
        this.restTemplate = new RestTemplate();
        this.rateLimiter = new RateLimiter("Football-Data.org", 10, 60_000); // 10 req/min
        this.matchCircuitBreaker = new CircuitBreaker<>("Football-Data-matches", 3, 60_000, Collections::emptyList);
        this.jsonCircuitBreaker = new CircuitBreaker<>("Football-Data-json", 3, 60_000, () -> objectMapper.createObjectNode());
        this.apiTimer = Timer.builder("external_api.latency")
                .tag("service", "football-data")
                .register(meterRegistry);
        this.rateLimitRejectionsCounter = Counter.builder("rate_limiter.rejections")
                .tag("name", "Football-Data.org")
                .register(meterRegistry);
    }

    public List<Map<String, Object>> getLeagues() {
        List<Map<String, Object>> leagues = new ArrayList<>();
        LEAGUE_CODES.forEach((code, name) -> {
            Map<String, Object> league = new LinkedHashMap<>();
            league.put("code", code);
            league.put("name", name);
            league.put("available", true);
            leagues.add(league);
        });
        return leagues;
    }

    public JsonNode getStandings(String leagueCode) {
        if (!rateLimiter.tryAcquire()) {
            log.warn("Rate limit exceeded for Football-Data.org (getStandings)");
            rateLimitRejectionsCounter.increment();
            return objectMapper.createObjectNode();
        }
        return apiTimer.record(() -> jsonCircuitBreaker.execute(() -> {
            String url = baseUrl + "/competitions/" + leagueCode + "/standings";
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, createEntity(), String.class);
            return parseJson(response.getBody());
        }));
    }

    public JsonNode getFixtures(String leagueCode) {
        if (!rateLimiter.tryAcquire()) {
            log.warn("Rate limit exceeded for Football-Data.org (getFixtures)");
            rateLimitRejectionsCounter.increment();
            return objectMapper.createObjectNode();
        }
        return apiTimer.record(() -> jsonCircuitBreaker.execute(() -> {
            String url = baseUrl + "/competitions/" + leagueCode + "/matches?status=SCHEDULED&limit=15";
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, createEntity(), String.class);
            return parseJson(response.getBody());
        }));
    }

    public List<Match> getLiveMatches() {
        if (!rateLimiter.tryAcquire()) {
            log.warn("Rate limit exceeded for Football-Data.org (getLiveMatches)");
            rateLimitRejectionsCounter.increment();
            return Collections.emptyList();
        }
        return apiTimer.record(() -> matchCircuitBreaker.execute(() -> {
            String url = baseUrl + "/matches?status=LIVE,IN_PLAY,PAUSED";
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, createEntity(), String.class);
            return parseMatches(parseJson(response.getBody()));
        }));
    }

    public List<Match> getTodaysMatches() {
        if (!rateLimiter.tryAcquire()) {
            log.warn("Rate limit exceeded for Football-Data.org (getTodaysMatches)");
            rateLimitRejectionsCounter.increment();
            return Collections.emptyList();
        }
        return apiTimer.record(() -> matchCircuitBreaker.execute(() -> {
            String url = baseUrl + "/matches";
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, createEntity(), String.class);
            return parseMatches(parseJson(response.getBody()));
        }));
    }

    public JsonNode getCompetitionTeams(String competitionCode) {
        if (!rateLimiter.tryAcquire()) {
            log.warn("Rate limit exceeded for Football-Data.org (getCompetitionTeams)");
            rateLimitRejectionsCounter.increment();
            return objectMapper.createObjectNode();
        }
        return apiTimer.record(() -> jsonCircuitBreaker.execute(() -> {
            String url = baseUrl + "/competitions/" + competitionCode + "/teams";
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, createEntity(), String.class);
            return parseJson(response.getBody());
        }));
    }

    private List<Match> parseMatches(JsonNode root) {
        List<Match> matches = new ArrayList<>();
        JsonNode matchesNode = root.get("matches");
        if (matchesNode == null || !matchesNode.isArray()) return matches;

        for (JsonNode m : matchesNode) {
            Match match = new Match();
            match.setId(m.get("id").asInt());
            match.setStatus(m.get("status").asText());
            match.setUtcDate(m.get("utcDate").asText());
            match.setMatchday(m.has("matchday") ? m.get("matchday").asInt(0) : 0);

            JsonNode competition = m.get("competition");
            if (competition != null) {
                match.setLeagueCode(competition.has("code") ? competition.get("code").asText() : "");
                match.setLeagueName(competition.has("name") ? competition.get("name").asText() : "");
            }

            match.setHomeTeam(parseTeam(m.get("homeTeam")));
            match.setAwayTeam(parseTeam(m.get("awayTeam")));
            match.setScore(parseScore(m.get("score")));

            if (m.has("minute") && !m.get("minute").isNull()) {
                match.setMinute(m.get("minute").asInt());
            }

            matches.add(match);
        }
        return matches;
    }

    private Match.TeamInfo parseTeam(JsonNode node) {
        if (node == null) return null;
        Match.TeamInfo team = new Match.TeamInfo();
        team.setId(node.has("id") ? node.get("id").asInt() : 0);
        team.setName(node.has("name") ? node.get("name").asText() : "");
        team.setShortName(node.has("shortName") ? node.get("shortName").asText() : "");
        team.setCrest(node.has("crest") ? node.get("crest").asText() : "");
        return team;
    }

    private Match.Score parseScore(JsonNode node) {
        if (node == null) return null;
        Match.Score score = new Match.Score();
        score.setWinner(node.has("winner") && !node.get("winner").isNull() ? node.get("winner").asText() : null);
        JsonNode fullTime = node.get("fullTime");
        if (fullTime != null) {
            score.setHomeGoals(fullTime.has("home") && !fullTime.get("home").isNull() ? fullTime.get("home").asInt() : null);
            score.setAwayGoals(fullTime.has("away") && !fullTime.get("away").isNull() ? fullTime.get("away").asInt() : null);
        }
        return score;
    }

    private JsonNode parseJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse JSON response", e);
        }
    }

    private HttpEntity<Void> createEntity() {
        HttpHeaders headers = new HttpHeaders();
        if (apiKey != null && !apiKey.isEmpty()) {
            headers.set("X-Auth-Token", apiKey);
        }
        return new HttpEntity<>(headers);
    }

    public RateLimiter getRateLimiter() { return rateLimiter; }
    public CircuitBreaker<List<Match>> getMatchCircuitBreaker() { return matchCircuitBreaker; }
    public CircuitBreaker<JsonNode> getJsonCircuitBreaker() { return jsonCircuitBreaker; }
}
