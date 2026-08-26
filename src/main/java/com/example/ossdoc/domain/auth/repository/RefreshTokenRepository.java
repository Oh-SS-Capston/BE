package com.example.ossdoc.domain.auth.repository;

import com.example.ossdoc.domain.auth.entity.RefreshToken;
import com.example.ossdoc.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByUser(User user);

    void deleteByUser(User user);

    void deleteByToken(String token);
}