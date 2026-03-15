package com.example.ossdoc.global.security.jwt;

import com.example.ossdoc.domain.auth.exception.code.AuthErrorCode;
import com.example.ossdoc.domain.auth.exception.AuthException;
import com.example.ossdoc.domain.user.entity.User;
import com.example.ossdoc.domain.user.enums.UserRole;
import com.example.ossdoc.global.properties.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.List;

@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

    private final JwtProperties jwtProperties;

    private SecretKey secretKey;

    @PostConstruct
    public void init() {
        byte[] keyBytes = Decoders.BASE64.decode(jwtProperties.getSecret());
        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateAccessToken(User user) {
        return Jwts.builder()
                .issuer(jwtProperties.getIssuer())
                .subject(String.valueOf(user.getId()))
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .claim("tokenType", "access")
                .issuedAt(new java.util.Date())
                .expiration(new java.util.Date(System.currentTimeMillis() + jwtProperties.getAccessExpirationMs()))
                .signWith(secretKey)
                .compact();
    }

    public String generateRefreshToken(User user) {
        return Jwts.builder()
                .issuer(jwtProperties.getIssuer())
                .subject(String.valueOf(user.getId()))
                .claim("tokenType", "refresh")
                .issuedAt(new java.util.Date())
                .expiration(new java.util.Date(System.currentTimeMillis() + jwtProperties.getRefreshExpirationMs()))
                .signWith(secretKey)
                .compact();
    }

    public Authentication getAuthentication(String accessToken) {
        Claims claims = parseClaims(accessToken);

        String tokenType = claims.get("tokenType", String.class);
        if (!"access".equals(tokenType)) {
            throw new AuthException(AuthErrorCode.INVALID_TOKEN_TYPE);
        }

        Long userId = Long.parseLong(claims.getSubject());
        String email = claims.get("email", String.class);
        UserRole role = UserRole.valueOf(claims.get("role", String.class));

        AuthenticatedUser principal = AuthenticatedUser.builder()
                .userId(userId)
                .email(email)
                .role(role)
                .build();

        return new UsernamePasswordAuthenticationToken(
                principal,
                accessToken,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))
        );
    }

    public Long getUserIdFromRefreshToken(String refreshToken) {
        Claims claims = parseClaims(refreshToken);

        String tokenType = claims.get("tokenType", String.class);
        if (!"refresh".equals(tokenType)) {
            throw new AuthException(AuthErrorCode.INVALID_TOKEN_TYPE);
        }

        return Long.parseLong(claims.getSubject());
    }

    public long getAccessTokenMaxAgeSeconds() {
        return jwtProperties.getAccessExpirationMs() / 1000;
    }

    public long getRefreshTokenMaxAgeSeconds() {
        return jwtProperties.getRefreshExpirationMs() / 1000;
    }

    private Claims parseClaims(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            throw new AuthException(AuthErrorCode.EXPIRED_TOKEN);
        } catch (JwtException | IllegalArgumentException e) {
            throw new AuthException(AuthErrorCode.INVALID_TOKEN);
        }
    }
}