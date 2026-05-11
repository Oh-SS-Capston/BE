package com.example.ossdoc.global.config;

import com.example.ossdoc.global.properties.AuthProperties;
import com.example.ossdoc.global.security.jwt.JwtAuthenticationFilter;
import com.example.ossdoc.global.security.oauth.CustomOidcUserService;
import com.example.ossdoc.global.security.oauth.OAuth2LoginSuccessHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.LinkedHashMap;
import java.util.Map;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final CorsConfigurationSource corsConfigurationSource;
    private final CustomOidcUserService customOidcUserService;
    private final OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;
    private final AuthProperties authProperties;
    private final ObjectMapper objectMapper;
    private final ClientRegistrationRepository clientRegistrationRepository;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) ->
                                writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "AUTH_001", "인증이 필요합니다."))
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                writeError(response, HttpServletResponse.SC_FORBIDDEN, "AUTH_002", "접근 권한이 없습니다."))
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs/**",
                                "/actuator/health"
                        ).permitAll()
                        .requestMatchers("/oauth2/**", "/login/oauth2/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/me").authenticated()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .oauth2Login(oauth2 -> oauth2
                        .authorizationEndpoint(endpoint -> endpoint
                                .authorizationRequestResolver(
                                        googleAuthorizationRequestResolver(clientRegistrationRepository)
                                )
                        )
                        .userInfoEndpoint(userInfo -> userInfo
                                .oidcUserService(customOidcUserService)
                        )
                        .successHandler(oAuth2LoginSuccessHandler)
                        .failureHandler((request, response, exception) ->
                                response.sendRedirect(authProperties.getFrontendFailureRedirectUri()))
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    private OAuth2AuthorizationRequestResolver googleAuthorizationRequestResolver(
            ClientRegistrationRepository clientRegistrationRepository
    ) {
        DefaultOAuth2AuthorizationRequestResolver defaultResolver =
                new DefaultOAuth2AuthorizationRequestResolver(
                        clientRegistrationRepository,
                        "/oauth2/authorization"
                );

        return new OAuth2AuthorizationRequestResolver() {
            @Override
            public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
                return customize(request, defaultResolver.resolve(request));
            }

            @Override
            public OAuth2AuthorizationRequest resolve(HttpServletRequest request,
                                                      String clientRegistrationId) {
                return customize(request, defaultResolver.resolve(request, clientRegistrationId));
            }

            private OAuth2AuthorizationRequest customize(HttpServletRequest request,
                                                         OAuth2AuthorizationRequest authorizationRequest) {
                if (authorizationRequest == null) {
                    return null;
                }

                String prompt = request.getParameter("prompt");

                if (!"select_account".equals(prompt)) {
                    return authorizationRequest;
                }

                Map<String, Object> additionalParameters =
                        new LinkedHashMap<>(authorizationRequest.getAdditionalParameters());

                additionalParameters.put("prompt", "select_account");

                return OAuth2AuthorizationRequest.from(authorizationRequest)
                        .additionalParameters(additionalParameters)
                        .build();
            }
        };
    }

    private void writeError(HttpServletResponse response, int status, String code, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("isSuccess", false);
        body.put("code", code);
        body.put("message", message);
        body.put("result", null);

        objectMapper.writeValue(response.getWriter(), body);
    }
}