package com.soccerdashboard.service;

import com.soccerdashboard.model.FavoriteTeam;
import com.soccerdashboard.repository.FavoriteRepository;
import com.soccerdashboard.repository.UserRepository;
import com.soccerdashboard.workflow.WorkflowTracer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class FavoriteService {

    private final FavoriteRepository favoriteRepository;
    private final UserRepository userRepository;
    private final WorkflowTracer workflowTracer;

    public FavoriteService(FavoriteRepository favoriteRepository, UserRepository userRepository,
                           WorkflowTracer workflowTracer) {
        this.favoriteRepository = favoriteRepository;
        this.userRepository = userRepository;
        this.workflowTracer = workflowTracer;
    }

    public List<FavoriteTeam> getFavorites(String username) {
        WorkflowTracer.Trace trace = workflowTracer.startUserAction("Get favorites: " + username);
        trace.emitApiGateway("GET /api/favorites");

        Long userId = getUserId(username, trace);

        long dbStart = System.nanoTime();
        List<FavoriteTeam> favorites = favoriteRepository.findByUserId(userId);
        long dbMs = (System.nanoTime() - dbStart) / 1_000_000;

        trace.emitDbRead("favorite_teams", "SELECT " + favorites.size() + " favorites for user " + username, dbMs);
        trace.emitResponse(200, favorites.size());

        return favorites;
    }

    public FavoriteTeam addFavorite(String username, FavoriteTeam favorite) {
        WorkflowTracer.Trace trace = workflowTracer.startUserAction("Add favorite: " + favorite.getTeamName());
        trace.emitApiGateway("POST /api/favorites");

        Long userId = getUserId(username, trace);
        trace.emitAuthCheck(username, "JWT validated");

        favorite.setUserId(userId);

        long dbStart = System.nanoTime();
        FavoriteTeam saved = favoriteRepository.save(favorite);
        long dbMs = (System.nanoTime() - dbStart) / 1_000_000;

        trace.emitDbWrite("favorite_teams", "INSERT favorite: " + favorite.getTeamName(), dbMs);
        trace.emitResponse(201, 1);

        return saved;
    }

    @Transactional
    public void removeFavorite(String username, int teamId) {
        WorkflowTracer.Trace trace = workflowTracer.startUserAction("Remove favorite: teamId=" + teamId);
        trace.emitApiGateway("DELETE /api/favorites/" + teamId);

        Long userId = getUserId(username, trace);
        trace.emitAuthCheck(username, "JWT validated");

        long dbStart = System.nanoTime();
        favoriteRepository.deleteByUserIdAndTeamId(userId, teamId);
        long dbMs = (System.nanoTime() - dbStart) / 1_000_000;

        trace.emitDbWrite("favorite_teams", "DELETE favorite for teamId=" + teamId, dbMs);
        trace.emitResponse(200, 0);
    }

    private Long getUserId(String username, WorkflowTracer.Trace trace) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> {
                    trace.emitError("Auth", "User not found: " + username, 0);
                    return new RuntimeException("User not found");
                })
                .getId();
    }
}
