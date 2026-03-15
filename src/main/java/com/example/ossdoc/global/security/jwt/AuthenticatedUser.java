package com.example.ossdoc.global.security.jwt;

import com.example.ossdoc.domain.user.enums.UserRole;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class AuthenticatedUser {

    private Long userId;
    private String email;
    private UserRole role;
}