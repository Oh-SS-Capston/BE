package com.example.ossdoc.domain.artifact.controller;

import com.example.ossdoc.domain.artifact.dto.response.ArtifactJsonResponse;
import com.example.ossdoc.domain.artifact.service.ArtifactQueryService;
import com.example.ossdoc.domain.auth.exception.AuthException;
import com.example.ossdoc.domain.auth.exception.code.AuthErrorCode;
import com.example.ossdoc.global.apiPayload.ApiResponse;
import com.example.ossdoc.global.security.jwt.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/artifacts")
public class ArtifactController {

    private final ArtifactQueryService artifactQueryService;

    @GetMapping("/{artifactId}")
    public ApiResponse getJsonArtifact(
            @PathVariable Long artifactId,
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser
    ) {
        if (authenticatedUser == null) {
            throw new AuthException(AuthErrorCode.UNAUTHORIZED_USER);
        }

        ArtifactJsonResponse response = artifactQueryService.getJsonArtifact(
                artifactId,
                authenticatedUser.getUserId()
        );

        return ApiResponse.onSuccess(response);
    }
}