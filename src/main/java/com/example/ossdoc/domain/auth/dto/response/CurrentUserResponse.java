package com.example.ossdoc.domain.auth.dto.response;

public record CurrentUserResponse(
        Long userId,
        String email,
        String name,
        String role
) {
}