package com.soccerdashboard.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soccerdashboard.model.Match;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class DemoService {

    private static final Logger log = LoggerFactory.getLogger(DemoService.class);

    private final ObjectMapper objectMapper;
    private List<List<Match>> demoFrames = new ArrayList<>();
    private final AtomicInteger frameIndex = new AtomicInteger(0);

    public DemoService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        try {
            ClassPathResource resource = new ClassPathResource("demo/demo-match-data.json");
            if (resource.exists()) {
                InputStream is = resource.getInputStream();
                demoFrames = objectMapper.readValue(is, new TypeReference<>() {});
                log.info("Loaded {} demo frames", demoFrames.size());
            } else {
                log.info("No demo data found, generating synthetic demo data");
                demoFrames = generateSyntheticDemoData();
            }
        } catch (Exception e) {
            log.warn("Failed to load demo data, generating synthetic: {}", e.getMessage());
            demoFrames = generateSyntheticDemoData();
        }
    }

    public List<Match> getNextDemoFrame() {
        if (demoFrames.isEmpty()) return Collections.emptyList();
        int idx = frameIndex.getAndUpdate(i -> (i + 1) % demoFrames.size());
        return demoFrames.get(idx);
    }

    public void resetDemo() {
        frameIndex.set(0);
    }

    public int getTotalFrames() {
        return demoFrames.size();
    }

    public int getCurrentFrame() {
        return frameIndex.get();
    }

    private List<List<Match>> generateSyntheticDemoData() {
        List<List<Match>> frames = new ArrayList<>();

        // Simulate a 90-minute match compressed into ~30 frames (5 min demo at 10s intervals)
        String[][] teams = {
                {"Arsenal", "ARS", "65"},
                {"Chelsea", "CHE", "61"},
                {"Manchester City", "MCI", "50"},
                {"Liverpool", "LIV", "64"},
                {"Real Madrid", "RMA", "86"},
                {"Barcelona", "BAR", "81"}
        };

        // Events timeline: [frame, type, matchIndex, team, player, homeGoals, awayGoals]
        int[][] goalEvents = {
                {5, 0, 0},   // Match 0: home scores at frame 5 -> 1-0
                {10, 1, 0},  // Match 0: away scores at frame 10 -> 1-1
                {15, 0, 1},  // Match 1: home scores at frame 15 -> 1-0
                {18, 0, 0},  // Match 0: home scores at frame 18 -> 2-1
                {22, 1, 1},  // Match 1: away scores at frame 22 -> 1-1
                {25, 0, 2},  // Match 2: home scores at frame 25 -> 1-0
                {28, 0, 0},  // Match 0: home scores at frame 28 -> 3-1
        };

        int[] match0Home = {0}, match0Away = {0};
        int[] match1Home = {0}, match1Away = {0};
        int[] match2Home = {0}, match2Away = {0};
        int[][] scores = {match0Home, match0Away, match1Home, match1Away, match2Home, match2Away};

        for (int frame = 0; frame < 30; frame++) {
            // Apply goals for this frame
            for (int[] event : goalEvents) {
                if (event[0] == frame) {
                    int matchIdx = event[2];
                    if (event[1] == 0) scores[matchIdx * 2][0]++;
                    else scores[matchIdx * 2 + 1][0]++;
                }
            }

            int minute = frame * 3; // Each frame = 3 minutes

            List<Match> frameMatches = new ArrayList<>();

            // Match 0: Arsenal vs Chelsea (Premier League)
            frameMatches.add(createDemoMatch(501, "PL", "Premier League",
                    65, "Arsenal", "ARS", "https://crests.football-data.org/57.png",
                    61, "Chelsea", "CHE", "https://crests.football-data.org/61.png",
                    scores[0][0], scores[1][0], minute, frame < 15 ? "IN_PLAY" : frame == 15 ? "PAUSED" : frame < 29 ? "IN_PLAY" : "FINISHED"));

            // Match 1: Man City vs Liverpool (Premier League)
            frameMatches.add(createDemoMatch(502, "PL", "Premier League",
                    50, "Manchester City", "MCI", "https://crests.football-data.org/65.png",
                    64, "Liverpool", "LIV", "https://crests.football-data.org/64.png",
                    scores[2][0], scores[3][0], minute, frame < 15 ? "IN_PLAY" : frame == 15 ? "PAUSED" : frame < 29 ? "IN_PLAY" : "FINISHED"));

            // Match 2: Real Madrid vs Barcelona (La Liga)
            frameMatches.add(createDemoMatch(503, "PD", "La Liga",
                    86, "Real Madrid", "RMA", "https://crests.football-data.org/86.png",
                    81, "FC Barcelona", "BAR", "https://crests.football-data.org/81.png",
                    scores[4][0], scores[5][0], minute, frame < 15 ? "IN_PLAY" : frame == 15 ? "PAUSED" : frame < 29 ? "IN_PLAY" : "FINISHED"));

            frames.add(frameMatches);
        }

        return frames;
    }

    private Match createDemoMatch(int id, String leagueCode, String leagueName,
                                  int homeId, String homeName, String homeShort, String homeCrest,
                                  int awayId, String awayName, String awayShort, String awayCrest,
                                  int homeGoals, int awayGoals, int minute, String status) {
        Match match = new Match();
        match.setId(id);
        match.setLeagueCode(leagueCode);
        match.setLeagueName(leagueName);
        match.setStatus(status);
        match.setMinute(minute);
        match.setUtcDate(new Date().toString());

        Match.TeamInfo home = new Match.TeamInfo();
        home.setId(homeId);
        home.setName(homeName);
        home.setShortName(homeShort);
        home.setCrest(homeCrest);
        match.setHomeTeam(home);

        Match.TeamInfo away = new Match.TeamInfo();
        away.setId(awayId);
        away.setName(awayName);
        away.setShortName(awayShort);
        away.setCrest(awayCrest);
        match.setAwayTeam(away);

        Match.Score score = new Match.Score();
        score.setHomeGoals(homeGoals);
        score.setAwayGoals(awayGoals);
        if (homeGoals > awayGoals) score.setWinner("HOME_TEAM");
        else if (awayGoals > homeGoals) score.setWinner("AWAY_TEAM");
        else score.setWinner("DRAW");
        match.setScore(score);

        match.setEvents(new ArrayList<>());

        return match;
    }
}
