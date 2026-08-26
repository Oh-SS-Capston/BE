package com.example.ossdoc.domain.auth.dto.response;

public record NicknameCheckResponse(
        String nickname,
        boolean available
) {
}
