package com.example.ossdoc.global.security.oauth;

import com.example.ossdoc.domain.auth.exception.AuthException;
import com.example.ossdoc.domain.auth.exception.code.AuthErrorCode;
import com.example.ossdoc.domain.auth.service.AuthService;
import com.example.ossdoc.domain.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CustomOidcUserService extends OidcUserService {

    private final AuthService authService;

    @Override
    @Transactional
    public OidcUser loadUser(OidcUserRequest userRequest) {
        OidcUser oidcUser = super.loadUser(userRequest);

        String providerId = oidcUser.getSubject();
        String email = oidcUser.getEmail();
        String name = oidcUser.getFullName();
        Boolean emailVerified = oidcUser.getEmailVerified();

        if (providerId == null || providerId.isBlank()
                || email == null || email.isBlank()) {
            throw new AuthException(AuthErrorCode.OAUTH2_LOGIN_FAILED);
        }

        if (!Boolean.TRUE.equals(emailVerified)) {
            throw new AuthException(AuthErrorCode.GOOGLE_EMAIL_NOT_VERIFIED);
        }

        User user = authService.upsertGoogleUser(providerId, email, name);

        return new CustomOidcUser(
                user,
                oidcUser.getAuthorities(),
                oidcUser.getIdToken(),
                oidcUser.getUserInfo()
        );
    }
}