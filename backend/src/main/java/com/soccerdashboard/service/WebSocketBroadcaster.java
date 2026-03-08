package com.soccerdashboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.soccerdashboard.model.Match;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@Component
public class WebSocketBroadcaster extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(WebSocketBroadcaster.class);

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> subscriptions = new ConcurrentHashMap<>(); // sessionId -> leagues
    private final ObjectMapper objectMapper;

    public WebSocketBroadcaster(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.put(session.getId(), session);
        subscriptions.put(session.getId(), new CopyOnWriteArraySet<>());
        log.info("WebSocket client connected: {}. Total: {}", session.getId(), sessions.size());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            Map<String, Object> msg = objectMapper.readValue(message.getPayload(), Map.class);
            String action = (String) msg.get("action");

            if ("subscribe".equals(action)) {
                List<String> leagues = (List<String>) msg.get("leagues");
                if (leagues != null) {
                    subscriptions.computeIfAbsent(session.getId(), k -> new CopyOnWriteArraySet<>())
                            .addAll(leagues);
                    log.info("Client {} subscribed to: {}", session.getId(), leagues);
                }
            } else if ("unsubscribe".equals(action)) {
                List<String> leagues = (List<String>) msg.get("leagues");
                if (leagues != null) {
                    Set<String> subs = subscriptions.get(session.getId());
                    if (subs != null) subs.removeAll(leagues);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse WebSocket message: {}", e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        subscriptions.remove(session.getId());
        log.info("WebSocket client disconnected: {}. Total: {}", session.getId(), sessions.size());
    }

    public void broadcastScoreUpdate(Match match) {
        String leagueCode = match.getLeagueCode();
        Map<String, Object> payload = Map.of(
                "type", "SCORE_UPDATE",
                "match", match
        );
        broadcastToSubscribers(leagueCode, payload);
    }

    public void broadcastMatchEvent(Match match, Match.MatchEvent event) {
        String leagueCode = match.getLeagueCode();
        Map<String, Object> payload = Map.of(
                "type", "EVENT",
                "matchId", match.getId(),
                "event", event,
                "match", match
        );
        broadcastToSubscribers(leagueCode, payload);
    }

    public void broadcastDiff(DataDiffEngine.DataDiff diff) {
        for (Match match : diff.getScoreChanges()) {
            broadcastScoreUpdate(match);
        }
        for (Match match : diff.getStatusChanges()) {
            broadcastScoreUpdate(match);
        }
        for (Match match : diff.getNewMatches()) {
            broadcastScoreUpdate(match);
        }
    }

    public void broadcastAll(Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            TextMessage message = new TextMessage(json);
            for (WebSocketSession session : sessions.values()) {
                if (session.isOpen()) {
                    try {
                        session.sendMessage(message);
                    } catch (IOException e) {
                        log.warn("Failed to send to session {}: {}", session.getId(), e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to serialize broadcast payload", e);
        }
    }

    private void broadcastToSubscribers(String leagueCode, Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            TextMessage message = new TextMessage(json);

            for (Map.Entry<String, WebSocketSession> entry : sessions.entrySet()) {
                WebSocketSession session = entry.getValue();
                Set<String> subs = subscriptions.get(entry.getKey());

                // Send to all if no subscriptions set, or if subscribed to this league
                if (session.isOpen() && (subs == null || subs.isEmpty() || subs.contains(leagueCode))) {
                    try {
                        session.sendMessage(message);
                    } catch (IOException e) {
                        log.warn("Failed to send to session {}", entry.getKey());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to broadcast to subscribers", e);
        }
    }

    public int getSessionCount() {
        return sessions.size();
    }
}
