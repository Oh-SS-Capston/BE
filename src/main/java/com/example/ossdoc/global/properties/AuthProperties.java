package com.example.ossdoc.global.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.auth")
public class AuthProperties {

    private String frontendSuccessRedirectUri;
    private String frontendFailureRedirectUri;
    private String accessCookieName;
    private String refreshCookieName;
    private boolean secureCookie;
    private String sameSite;
}