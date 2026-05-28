package com.example.ossdoc.global.security.oauth;

import com.example.ossdoc.global.properties.AuthProperties;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

@Component
@Slf4j
@RequiredArgsConstructor
public class OAuth2LoginFailureHandler extends SimpleUrlAuthenticationFailureHandler {

    private final AuthProperties authProperties;

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception
    ) throws IOException, ServletException {
        HttpSession session = request.getSession(false);
        String sessionId = session == null ? "none" : session.getId();
        String oauth2ErrorCode = null;

        if (exception instanceof OAuth2AuthenticationException oauth2AuthenticationException) {
            oauth2ErrorCode = oauth2AuthenticationException.getError().getErrorCode();
        }

        log.debug(
                "[AUTH][OAUTH2] Login failed. oauth2ErrorCode={}, exceptionType={}, message={}, requestUri={}, state={}, sessionId={}, remoteIp={}",
                oauth2ErrorCode,
                exception.getClass().getSimpleName(),
                exception.getMessage(),
                request.getRequestURI(),
                request.getParameter("state"),
                sessionId,
                request.getRemoteAddr()
        );

        String redirectUri = UriComponentsBuilder
                .fromUriString(authProperties.getFrontendFailureRedirectUri())
                .queryParam("message", "Google login failed.")
                .build()
                .toUriString();

        getRedirectStrategy().sendRedirect(request, response, redirectUri);
    }
}
