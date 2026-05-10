package com.example.ossdoc.domain.rule.controller;

import com.example.ossdoc.domain.auth.exception.AuthException;
import com.example.ossdoc.domain.auth.exception.code.AuthErrorCode;
import com.example.ossdoc.domain.rule.dto.request.RuleCandidateMineRequest;
import com.example.ossdoc.domain.rule.dto.response.RuleCandidateMineResponse;
import com.example.ossdoc.domain.rule.service.RuleCandidateMiningService;
import com.example.ossdoc.global.apiPayload.ApiResponse;
import com.example.ossdoc.global.security.jwt.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/rule-candidates")
public class RuleCandidateController {

    private final RuleCandidateMiningService ruleCandidateMiningService;

    @PostMapping("/mine")
    @Operation(summary = "GraphStore 기반 Rule Candidate Mining 실행")
    public ApiResponse<RuleCandidateMineResponse> mine(
            @Valid @RequestBody RuleCandidateMineRequest request,
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser
    ) {
        if (authenticatedUser == null) {
            throw new AuthException(AuthErrorCode.UNAUTHORIZED_USER);
        }

        RuleCandidateMineResponse response =
                ruleCandidateMiningService.mine(request, authenticatedUser.getUserId());

        return ApiResponse.onSuccess(response);
    }
}