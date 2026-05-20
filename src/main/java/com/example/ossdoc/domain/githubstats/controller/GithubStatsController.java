package com.example.ossdoc.domain.githubstats.controller;

import com.example.ossdoc.domain.auth.exception.AuthException;
import com.example.ossdoc.domain.auth.exception.code.AuthErrorCode;
import com.example.ossdoc.domain.githubstats.dto.response.GithubStatsResponse;
import com.example.ossdoc.domain.githubstats.service.GithubStatsService;
import com.example.ossdoc.global.apiPayload.ApiResponse;
import com.example.ossdoc.global.security.jwt.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/runs")
public class GithubStatsController {

    private final GithubStatsService githubStatsService;

    /*
     * 분석 완료 run 기준 GitHub 사용 통계량 조회 API입니다.
     *
     * GET /api/v1/runs/{runId}/github-stats
     * GET /api/v1/runs/{runId}/github-stats?forceRefresh=true
     */
    @GetMapping("/{runId}/github-stats")
    public ApiResponse<GithubStatsResponse> getGithubStats(
            @PathVariable String runId,
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser
    ) {
        if (authenticatedUser == null) {
            throw new AuthException(AuthErrorCode.UNAUTHORIZED_USER);
        }

        GithubStatsResponse response = githubStatsService.getStats(
                runId,
                authenticatedUser.getUserId(),
                forceRefresh
        );

        return ApiResponse.onSuccess(response);
    }
}