package com.example.ossdoc.global.security.oauth;

import com.example.ossdoc.domain.auth.exception.AuthException;
import com.example.ossdoc.domain.auth.exception.code.AuthErrorCode;
import com.example.ossdoc.domain.auth.service.AuthService;
import com.example.ossdoc.domain.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final AuthService authService;

    @Override
    @Transactional
    public OAuth2User loadUser(OAuth2UserRequest userRequest) {
        OAuth2User oauth2User = super.loadUser(userRequest);

        String providerId = getStringAttribute(oauth2User, "sub");
        String email = getStringAttribute(oauth2User, "email");
        String name = getStringAttribute(oauth2User, "name");
        Boolean emailVerified = getBooleanAttribute(oauth2User, "email_verified");

        if (providerId == null || providerId.isBlank()
                || email == null || email.isBlank()) {
            throw new AuthException(AuthErrorCode.OAUTH2_LOGIN_FAILED);
        }

        if (!Boolean.TRUE.equals(emailVerified)) {
            throw new AuthException(AuthErrorCode.GOOGLE_EMAIL_NOT_VERIFIED);
        }

        User user = authService.upsertGoogleUser(providerId, email, name);
        return new CustomOAuth2User(user, oauth2User);
    }

    private String getStringAttribute(OAuth2User user, String key) {
        Object value = user.getAttributes().get(key);
        return value == null ? null : value.toString();
    }

    private Boolean getBooleanAttribute(OAuth2User user, String key) {
        Object value = user.getAttributes().get(key);

        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }

        if (value instanceof String stringValue) {
            return Boolean.parseBoolean(stringValue);
        }

        return null;
    }
}