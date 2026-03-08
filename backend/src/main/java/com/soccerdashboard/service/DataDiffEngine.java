package com.soccerdashboard.service;

import com.soccerdashboard.model.Match;

import java.util.*;
import java.util.stream.Collectors;

public class DataDiffEngine {

    public static class DataDiff {
        private final List<Match> scoreChanges;
        private final List<Match> statusChanges;
        private final List<Match> newMatches;
        private int totalChanges;

        public DataDiff() {
            this.scoreChanges = new ArrayList<>();
            this.statusChanges = new ArrayList<>();
            this.newMatches = new ArrayList<>();
        }

        public List<Match> getScoreChanges() { return scoreChanges; }
        public List<Match> getStatusChanges() { return statusChanges; }
        public List<Match> getNewMatches() { return newMatches; }
        public int getTotalChanges() { return scoreChanges.size() + statusChanges.size() + newMatches.size(); }
        public boolean hasChanges() { return getTotalChanges() > 0; }
    }

    public static DataDiff diff(List<Match> previous, List<Match> current) {
        DataDiff result = new DataDiff();

        Map<Integer, Match> prevMap = previous.stream()
                .collect(Collectors.toMap(Match::getId, m -> m, (a, b) -> b));

        for (Match curr : current) {
            Match prev = prevMap.get(curr.getId());

            if (prev == null) {
                result.newMatches.add(curr);
                continue;
            }

            // Check score changes
            if (scoresChanged(prev, curr)) {
                result.scoreChanges.add(curr);
            }

            // Check status changes
            if (!Objects.equals(prev.getStatus(), curr.getStatus())) {
                result.statusChanges.add(curr);
            }
        }

        return result;
    }

    private static boolean scoresChanged(Match prev, Match curr) {
        if (prev.getScore() == null || curr.getScore() == null) return false;
        return !Objects.equals(prev.getScore().getHomeGoals(), curr.getScore().getHomeGoals())
                || !Objects.equals(prev.getScore().getAwayGoals(), curr.getScore().getAwayGoals());
    }
}
