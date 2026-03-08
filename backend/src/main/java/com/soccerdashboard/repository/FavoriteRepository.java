package com.soccerdashboard.repository;

import com.soccerdashboard.model.FavoriteTeam;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FavoriteRepository extends JpaRepository<FavoriteTeam, Long> {
    List<FavoriteTeam> findByUserId(Long userId);
    Optional<FavoriteTeam> findByUserIdAndTeamId(Long userId, int teamId);
    void deleteByUserIdAndTeamId(Long userId, int teamId);
}
