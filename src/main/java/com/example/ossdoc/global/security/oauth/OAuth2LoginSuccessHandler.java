package com.example.ossdoc.global.security.oauth;

import com.example.ossdoc.domain.auth.exception.AuthException;
import com.example.ossdoc.domain.auth.exception.code.AuthErrorCode;
import com.example.ossdoc.domain.auth.service.AuthService;
import com.example.ossdoc.domain.user.entity.User;
import com.example.ossdoc.global.properties.AuthProperties;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class OAuth2LoginSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final AuthService authService;
    private final AuthProperties authProperties;

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication
    ) throws IOException, ServletException {
        User user = extractUser(authentication);

        if (!user.isActive()) {
            throw new AuthException(AuthErrorCode.INACTIVE_USER);
        }

        authService.issueLoginTokens(user, response);

        getRedirectStrategy().sendRedirect(
                request,
                response,
                authProperties.getFrontendSuccessRedirectUri()
        );
    }

    private User extractUser(Authentication authentication) {
        Object principal = authentication.getPrincipal();

        if (principal instanceof CustomOidcUser customOidcUser) {
            return customOidcUser.getUser();
        }

        if (principal instanceof CustomOAuth2User customOAuth2User) {
            return customOAuth2User.getUser();
        }

        throw new AuthException(AuthErrorCode.OAUTH2_LOGIN_FAILED);
    }
}