package com.example.ossdoc.domain.auth.service;

import com.example.ossdoc.domain.auth.dto.response.CurrentUserResponse;
import com.example.ossdoc.domain.auth.entity.RefreshToken;
import com.example.ossdoc.domain.auth.exception.code.AuthErrorCode;
import com.example.ossdoc.domain.auth.exception.AuthException;
import com.example.ossdoc.domain.auth.repository.RefreshTokenRepository;
import com.example.ossdoc.domain.user.entity.User;
import com.example.ossdoc.domain.user.repository.UserRepository;
import com.example.ossdoc.global.properties.AuthProperties;
import com.example.ossdoc.global.security.jwt.AuthenticatedUser;
import com.example.ossdoc.global.security.jwt.JwtTokenProvider;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final AuthProperties authProperties;

    @Transactional
    public void issueLoginTokens(User user, HttpServletResponse response) {
        String accessToken = jwtTokenProvider.generateAccessToken(user);
        String refreshToken = jwtTokenProvider.generateRefreshToken(user);

        LocalDateTime refreshExpiresAt =
                LocalDateTime.now().plusSeconds(jwtTokenProvider.getRefreshTokenMaxAgeSeconds());

        refreshTokenRepository.findByUser(user)
                .ifPresentOrElse(
                        saved -> saved.rotate(refreshToken, refreshExpiresAt),
                        () -> refreshTokenRepository.save(
                                RefreshToken.builder()
                                        .user(user)
                                        .token(refreshToken)
                                        .expiresAt(refreshExpiresAt)
                                        .build()
                        )
                );

        addCookie(response, authProperties.getAccessCookieName(), accessToken, jwtTokenProvider.getAccessTokenMaxAgeSeconds());
        addCookie(response, authProperties.getRefreshCookieName(), refreshToken, jwtTokenProvider.getRefreshTokenMaxAgeSeconds());
    }

    @Transactional
    public void refresh(HttpServletRequest request, HttpServletResponse response) {
        String refreshToken = getCookieValue(request, authProperties.getRefreshCookieName());
        if (refreshToken == null) {
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_NOT_FOUND);
        }

        Long userId = jwtTokenProvider.getUserIdFromRefreshToken(refreshToken);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthException(AuthErrorCode.USER_NOT_FOUND));

        RefreshToken savedRefreshToken = refreshTokenRepository.findByUser(user)
                .orElseThrow(() -> new AuthException(AuthErrorCode.REFRESH_TOKEN_NOT_FOUND));

        if (savedRefreshToken.isExpired(LocalDateTime.now())) {
            throw new AuthException(AuthErrorCode.EXPIRED_TOKEN);
        }

        if (!savedRefreshToken.matches(refreshToken)) {
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_MISMATCH);
        }

        String newAccessToken = jwtTokenProvider.generateAccessToken(user);
        String newRefreshToken = jwtTokenProvider.generateRefreshToken(user);

        savedRefreshToken.rotate(
                newRefreshToken,
                LocalDateTime.now().plusSeconds(jwtTokenProvider.getRefreshTokenMaxAgeSeconds())
        );

        addCookie(response, authProperties.getAccessCookieName(), newAccessToken, jwtTokenProvider.getAccessTokenMaxAgeSeconds());
        addCookie(response, authProperties.getRefreshCookieName(), newRefreshToken, jwtTokenProvider.getRefreshTokenMaxAgeSeconds());
    }

    @Transactional
    public void logout(HttpServletRequest request,
                       HttpServletResponse response,
                       AuthenticatedUser authenticatedUser) {

        if (authenticatedUser != null) {
            userRepository.findById(authenticatedUser.getUserId())
                    .ifPresent(refreshTokenRepository::deleteByUser);
        }

        String refreshToken = getCookieValue(request, authProperties.getRefreshCookieName());

        if (refreshToken != null) {
            refreshTokenRepository.deleteByToken(refreshToken);
        }

        deleteCookie(response, authProperties.getAccessCookieName());
        deleteCookie(response, authProperties.getRefreshCookieName());
        deleteCookie(response, "JSESSIONID");

        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }

        SecurityContextHolder.clearContext();
    }

    public CurrentUserResponse getCurrentUser(AuthenticatedUser authenticatedUser) {
        if (authenticatedUser == null) {
            throw new AuthException(AuthErrorCode.AUTHENTICATION_REQUIRED);
        }

        User user = userRepository.findById(authenticatedUser.getUserId())
                .orElseThrow(() -> new AuthException(AuthErrorCode.USER_NOT_FOUND));

        return new CurrentUserResponse(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getRole().name()
        );
    }

    private void addCookie(HttpServletResponse response, String name, String value, long maxAgeSeconds) {
        ResponseCookie cookie = ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(authProperties.isSecureCookie())
                .path("/")
                .sameSite(authProperties.getSameSite())
                .maxAge(maxAgeSeconds)
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void deleteCookie(HttpServletResponse response, String name) {
        ResponseCookie cookie = ResponseCookie.from(name, "")
                .httpOnly(true)
                .secure(authProperties.isSecureCookie())
                .path("/")
                .sameSite(authProperties.getSameSite())
                .maxAge(0)
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private String getCookieValue(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return null;
        }

        for (Cookie cookie : request.getCookies()) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}