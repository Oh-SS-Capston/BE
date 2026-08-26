package com.example.ossdoc.domain.auth.service;

import com.example.ossdoc.domain.auth.dto.request.NicknameUpdateRequest;
import com.example.ossdoc.domain.auth.dto.response.CurrentUserResponse;
import com.example.ossdoc.domain.auth.dto.response.NicknameCheckResponse;
import com.example.ossdoc.domain.auth.entity.RefreshToken;
import com.example.ossdoc.domain.auth.exception.AuthException;
import com.example.ossdoc.domain.auth.exception.code.AuthErrorCode;
import com.example.ossdoc.domain.auth.repository.RefreshTokenRepository;
import com.example.ossdoc.domain.user.entity.User;
import com.example.ossdoc.domain.user.enums.AuthProvider;
import com.example.ossdoc.domain.user.repository.UserRepository;
import com.example.ossdoc.global.properties.AuthProperties;
import com.example.ossdoc.global.security.jwt.AuthenticatedUser;
import com.example.ossdoc.global.security.jwt.JwtTokenProvider;
import com.example.ossdoc.global.properties.AccountPolicyProperties;
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
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    private static final Pattern NICKNAME_PATTERN =
            Pattern.compile("^[가-힣a-zA-Z0-9_]{2,10}$");

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final AuthProperties authProperties;
    private final AccountPolicyProperties accountPolicyProperties;

    /**
     * Google OAuth 성공 시 호출됩니다.
     * 자체 회원가입은 없고, Google 계정 기준으로 사용자를 생성하거나 갱신합니다.
     */
    @Transactional
    public User upsertGoogleUser(
            String providerId,
            String email,
            String googleName
    ) {
        if (providerId == null || providerId.isBlank()
                || email == null || email.isBlank()) {
            throw new AuthException(AuthErrorCode.OAUTH2_LOGIN_FAILED);
        }

        String normalizedEmail = normalizeEmail(email);
        LocalDateTime now = LocalDateTime.now();

        return userRepository.findByProviderAndProviderId(AuthProvider.GOOGLE, providerId)
                .map(user -> {
                    if (!user.isActive()) {
                        if (!user.canReactivateAfter(
                                now,
                                accountPolicyProperties.getRejoinWaitDays()
                        )) {
                            throw new AuthException(AuthErrorCode.REJOIN_WAIT_PERIOD_NOT_PASSED);
                        }

                        user.reactivate(now);
                    }

                    user.updateGoogleEmail(normalizedEmail);
                    return user;
                })
                .orElseGet(() -> {
                    String baseNickname = resolveBaseNickname(normalizedEmail, googleName);
                    String uniqueNickname = generateUniqueNickname(baseNickname);

                    User newUser = User.createGoogleUser(
                            providerId,
                            normalizedEmail,
                            uniqueNickname
                    );

                    return userRepository.save(newUser);
                });
    }

    @Transactional
    public void issueLoginTokens(User user, HttpServletResponse response) {
        validateActiveUser(user);

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

        addCookie(
                response,
                authProperties.getAccessCookieName(),
                accessToken,
                jwtTokenProvider.getAccessTokenMaxAgeSeconds()
        );

        addCookie(
                response,
                authProperties.getRefreshCookieName(),
                refreshToken,
                jwtTokenProvider.getRefreshTokenMaxAgeSeconds()
        );
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

        validateActiveUser(user);

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

        addCookie(
                response,
                authProperties.getAccessCookieName(),
                newAccessToken,
                jwtTokenProvider.getAccessTokenMaxAgeSeconds()
        );

        addCookie(
                response,
                authProperties.getRefreshCookieName(),
                newRefreshToken,
                jwtTokenProvider.getRefreshTokenMaxAgeSeconds()
        );
    }

    public CurrentUserResponse getCurrentUser(AuthenticatedUser authenticatedUser) {
        User user = getAuthenticatedActiveUser(authenticatedUser);
        return toCurrentUserResponse(user);
    }

    public NicknameCheckResponse checkNickname(
            String nickname,
            AuthenticatedUser authenticatedUser
    ) {
        User user = getAuthenticatedActiveUser(authenticatedUser);
        String normalized = normalizeNickname(nickname);

        validateNicknameFormat(normalized);

        boolean available = !userRepository.existsByNicknameAndIdNot(
                normalized,
                user.getId()
        );

        return new NicknameCheckResponse(normalized, available);
    }

    @Transactional
    public CurrentUserResponse updateNickname(
            AuthenticatedUser authenticatedUser,
            NicknameUpdateRequest request
    ) {
        User user = getAuthenticatedActiveUser(authenticatedUser);
        String nickname = normalizeNickname(request.nickname());

        validateNicknameFormat(nickname);

        if (userRepository.existsByNicknameAndIdNot(nickname, user.getId())) {
            throw new AuthException(AuthErrorCode.DUPLICATE_NICKNAME);
        }

        user.updateNickname(nickname);
        return toCurrentUserResponse(user);
    }

    @Transactional
    public void logout(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticatedUser authenticatedUser
    ) {
        if (authenticatedUser != null) {
            userRepository.findById(authenticatedUser.getUserId())
                    .ifPresent(refreshTokenRepository::deleteByUser);
        }

        String refreshToken = getCookieValue(request, authProperties.getRefreshCookieName());

        if (refreshToken != null) {
            refreshTokenRepository.deleteByToken(refreshToken);
        }

        clearLoginState(request, response);
    }

    @Transactional
    public void deleteAccount(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticatedUser authenticatedUser
    ) {
        User user = getAuthenticatedActiveUser(authenticatedUser);

        refreshTokenRepository.deleteByUser(user);
        user.deactivate(LocalDateTime.now());

        clearLoginState(request, response);
    }

    private User getAuthenticatedActiveUser(AuthenticatedUser authenticatedUser) {
        if (authenticatedUser == null) {
            throw new AuthException(AuthErrorCode.AUTHENTICATION_REQUIRED);
        }

        User user = userRepository.findById(authenticatedUser.getUserId())
                .orElseThrow(() -> new AuthException(AuthErrorCode.USER_NOT_FOUND));

        validateActiveUser(user);
        return user;
    }

    private void validateActiveUser(User user) {
        if (!user.isActive()) {
            throw new AuthException(AuthErrorCode.INACTIVE_USER);
        }
    }

    private CurrentUserResponse toCurrentUserResponse(User user) {
        return new CurrentUserResponse(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getNickname(),
                user.getRole().name(),
                user.getProvider().name()
        );
    }

    private String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }

    private String normalizeNickname(String nickname) {
        return nickname == null ? null : nickname.trim();
    }

    private void validateNicknameFormat(String nickname) {
        if (nickname == null || !NICKNAME_PATTERN.matcher(nickname).matches()) {
            throw new AuthException(AuthErrorCode.INVALID_NICKNAME);
        }
    }

    private String resolveBaseNickname(String email, String googleName) {
        String source = googleName;

        if (source == null || source.isBlank()) {
            source = email.substring(0, email.indexOf("@"));
        }

        String normalized = source
                .replaceAll("[^가-힣a-zA-Z0-9_]", "")
                .trim();

        if (normalized.length() < 2) {
            normalized = email.substring(0, email.indexOf("@"))
                    .replaceAll("[^a-zA-Z0-9_]", "");
        }

        if (normalized.length() < 2) {
            normalized = "user";
        }

        if (normalized.length() > 10) {
            normalized = normalized.substring(0, 10);
        }

        return normalized;
    }

    private String generateUniqueNickname(String baseNickname) {
        if (!userRepository.existsByNickname(baseNickname)) {
            return baseNickname;
        }

        for (int i = 1; i <= 9999; i++) {
            String suffix = "_" + i;
            int maxBaseLength = 10 - suffix.length();

            String candidateBase = baseNickname.length() > maxBaseLength
                    ? baseNickname.substring(0, maxBaseLength)
                    : baseNickname;

            String candidate = candidateBase + suffix;

            if (!userRepository.existsByNickname(candidate)) {
                return candidate;
            }
        }

        return "user_" + System.currentTimeMillis();
    }

    private void clearLoginState(HttpServletRequest request, HttpServletResponse response) {
        deleteCookie(response, authProperties.getAccessCookieName());
        deleteCookie(response, authProperties.getRefreshCookieName());
        deleteCookie(response, "JSESSIONID");

        HttpSession session = request.getSession(false);

        if (session != null) {
            session.invalidate();
        }

        SecurityContextHolder.clearContext();
    }

    private void addCookie(
            HttpServletResponse response,
            String name,
            String value,
            long maxAgeSeconds
    ) {
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