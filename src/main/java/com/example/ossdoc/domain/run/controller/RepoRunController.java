package com.example.ossdoc.domain.run.controller;

import com.example.ossdoc.domain.auth.exception.AuthException;
import com.example.ossdoc.domain.auth.exception.code.AuthErrorCode;
import com.example.ossdoc.domain.run.dto.request.RepoRunCreateRequest;
import com.example.ossdoc.domain.run.dto.response.RepoRunCreateResponse;
import com.example.ossdoc.domain.run.dto.response.RepoRunProgressResponse;
import com.example.ossdoc.domain.run.service.RepoRunService;
import com.example.ossdoc.domain.run.service.RunProgressQueryService;
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
    private final RunProgressQueryService runProgressQueryService;

    /*
     * 프론트는 이 API만 호출해서 분석을 요청합니다.
     * 이후 전체 pipeline은 백엔드 worker가 자동으로 처리합니다.
     */
    @PostMapping
    public ApiResponse<RepoRunCreateResponse> create(
            @Valid @RequestBody RepoRunCreateRequest request,
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser
    ) {
        if (authenticatedUser == null) {
            throw new AuthException(AuthErrorCode.UNAUTHORIZED_USER);
        }

        RepoRunCreateResponse response = repoRunService.createRun(
                request,
                authenticatedUser.getUserId()
        );

        return ApiResponse.onSuccess(response);
    }

    /*
     * 프론트 진행률 바 polling API입니다.
     *
     * GET /api/v1/runs/{runId}/progress
     */
    @GetMapping("/{runId}/progress")
    public ApiResponse<RepoRunProgressResponse> progress(
            @PathVariable String runId,
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser
    ) {
        if (authenticatedUser == null) {
            throw new AuthException(AuthErrorCode.UNAUTHORIZED_USER);
        }

        RepoRunProgressResponse response = runProgressQueryService.getProgress(
                runId,
                authenticatedUser.getUserId()
        );

        return ApiResponse.onSuccess(response);
    }
}