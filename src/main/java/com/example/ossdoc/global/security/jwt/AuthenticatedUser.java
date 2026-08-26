package com.example.ossdoc.global.security.jwt;

import com.example.ossdoc.domain.user.entity.User;
import com.example.ossdoc.domain.user.enums.UserRole;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class AuthenticatedUser {

    private final Long userId;
    private final String email;
    private final String nickname;

    /**
     * 현재 권한 기반 처리는 사용하지 않습니다.
     * 다만 기존 User 구조와 JWT 확장 가능성을 위해 값만 보관합니다.
     */
    private final UserRole role;

    /**
     * 탈퇴 계정 차단용입니다.
     */
    private final boolean active;

    public static AuthenticatedUser from(User user) {
        return AuthenticatedUser.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .nickname(user.getNickname())
                .role(user.getRole())
                .active(user.isActive())
                .build();
    }
}