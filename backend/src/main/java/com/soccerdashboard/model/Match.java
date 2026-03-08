package com.soccerdashboard.model;

import java.io.Serializable;
import java.util.List;

public class Match implements Serializable {

    private int id;
    private String status; // SCHEDULED, TIMED, IN_PLAY, PAUSED, FINISHED, POSTPONED, CANCELLED
    private int matchday;
    private String utcDate;
    private int minute;
    private String leagueCode;
    private String leagueName;

    private TeamInfo homeTeam;
    private TeamInfo awayTeam;
    private Score score;
    private List<MatchEvent> events;

    public static class TeamInfo implements Serializable {
        private int id;
        private String name;
        private String shortName;
        private String crest;

        public int getId() { return id; }
        public void setId(int id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getShortName() { return shortName; }
        public void setShortName(String shortName) { this.shortName = shortName; }
        public String getCrest() { return crest; }
        public void setCrest(String crest) { this.crest = crest; }
    }

    public static class Score implements Serializable {
        private Integer homeGoals;
        private Integer awayGoals;
        private String winner; // HOME_TEAM, AWAY_TEAM, DRAW

        public Integer getHomeGoals() { return homeGoals; }
        public void setHomeGoals(Integer homeGoals) { this.homeGoals = homeGoals; }
        public Integer getAwayGoals() { return awayGoals; }
        public void setAwayGoals(Integer awayGoals) { this.awayGoals = awayGoals; }
        public String getWinner() { return winner; }
        public void setWinner(String winner) { this.winner = winner; }
    }

    public static class MatchEvent implements Serializable {
        private String type; // GOAL, YELLOW_CARD, RED_CARD, SUBSTITUTION
        private int minute;
        private String team;
        private String player;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public int getMinute() { return minute; }
        public void setMinute(int minute) { this.minute = minute; }
        public String getTeam() { return team; }
        public void setTeam(String team) { this.team = team; }
        public String getPlayer() { return player; }
        public void setPlayer(String player) { this.player = player; }
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getMatchday() { return matchday; }
    public void setMatchday(int matchday) { this.matchday = matchday; }
    public String getUtcDate() { return utcDate; }
    public void setUtcDate(String utcDate) { this.utcDate = utcDate; }
    public int getMinute() { return minute; }
    public void setMinute(int minute) { this.minute = minute; }
    public String getLeagueCode() { return leagueCode; }
    public void setLeagueCode(String leagueCode) { this.leagueCode = leagueCode; }
    public String getLeagueName() { return leagueName; }
    public void setLeagueName(String leagueName) { this.leagueName = leagueName; }
    public TeamInfo getHomeTeam() { return homeTeam; }
    public void setHomeTeam(TeamInfo homeTeam) { this.homeTeam = homeTeam; }
    public TeamInfo getAwayTeam() { return awayTeam; }
    public void setAwayTeam(TeamInfo awayTeam) { this.awayTeam = awayTeam; }
    public Score getScore() { return score; }
    public void setScore(Score score) { this.score = score; }
    public List<MatchEvent> getEvents() { return events; }
    public void setEvents(List<MatchEvent> events) { this.events = events; }
}
