package com.example.ossdoc.domain.run.controller;

import com.example.ossdoc.domain.auth.exception.AuthException;
import com.example.ossdoc.domain.auth.exception.code.AuthErrorCode;
import com.example.ossdoc.domain.run.dto.RepoRunCreateRequest;
import com.example.ossdoc.domain.run.dto.RepoRunCreateResponse;
import com.example.ossdoc.domain.run.service.RepoRunService;
import com.example.ossdoc.global.apiPayload.ApiResponse;
import com.example.ossdoc.global.security.jwt.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/runs")
public class RepoRunController {

    private final RepoRunService repoRunService;

    @PostMapping
    public ApiResponse<RepoRunCreateResponse> create(@Valid @RequestBody RepoRunCreateRequest request,
                                                     @AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
        if (authenticatedUser == null) {
            throw new AuthException(AuthErrorCode.UNAUTHORIZED_USER);
        }

        RepoRunCreateResponse response = repoRunService.createRun(request, authenticatedUser.getUserId());
        return ApiResponse.onSuccess(response);
    }
}