package com.soccerdashboard.controller;

import com.soccerdashboard.model.FavoriteTeam;
import com.soccerdashboard.service.FavoriteService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/favorites")
public class FavoriteController {

    private final FavoriteService favoriteService;

    public FavoriteController(FavoriteService favoriteService) {
        this.favoriteService = favoriteService;
    }

    @GetMapping
    public ResponseEntity<List<FavoriteTeam>> getFavorites(@AuthenticationPrincipal UserDetails user) {
        return ResponseEntity.ok(favoriteService.getFavorites(user.getUsername()));
    }

    @PostMapping
    public ResponseEntity<?> addFavorite(@AuthenticationPrincipal UserDetails user,
                                         @RequestBody FavoriteTeam favorite) {
        try {
            FavoriteTeam saved = favoriteService.addFavorite(user.getUsername(), favorite);
            return ResponseEntity.status(HttpStatus.CREATED).body(saved);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{teamId}")
    public ResponseEntity<?> removeFavorite(@AuthenticationPrincipal UserDetails user,
                                            @PathVariable int teamId) {
        favoriteService.removeFavorite(user.getUsername(), teamId);
        return ResponseEntity.ok(Map.of("message", "Favorite removed"));
    }
}
