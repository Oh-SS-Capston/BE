package com.example.ossdoc.global.security.oauth;

import com.example.ossdoc.domain.user.entity.User;
import lombok.Getter;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.Collection;
import java.util.Map;

@Getter
public class CustomOAuth2User implements OAuth2User {

    private final User user;
    private final OAuth2User delegate;

    public CustomOAuth2User(User user, OAuth2User delegate) {
        this.user = user;
        this.delegate = delegate;
    }

    @Override
    public Map<String, Object> getAttributes() {
        return delegate.getAttributes();
    }

    /**
     * 현재 서비스는 권한 기반 처리를 사용하지 않습니다.
     * Google OAuth 기본 authority를 그대로 반환하거나 빈 리스트를 반환해도 됩니다.
     */
    @Override
    public Collection getAuthorities() {
        return delegate.getAuthorities();
    }

    @Override
    public String getName() {
        return user.getEmail();
    }
}