package com.example.ossdoc.domain.token.controller;

import com.example.ossdoc.domain.auth.exception.AuthException;
import com.example.ossdoc.domain.auth.exception.code.AuthErrorCode;
import com.example.ossdoc.domain.token.dto.response.TokenBalanceResponse;
import com.example.ossdoc.domain.token.dto.response.TokenLedgerResponse;
import com.example.ossdoc.domain.token.service.TokenService;
import com.example.ossdoc.global.apiPayload.ApiResponse;
import com.example.ossdoc.global.security.jwt.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/tokens")
public class TokenController {

    private final TokenService tokenService;

    /*
     * 로그인한 사용자의 토큰 잔액을 조회합니다.
     *
     * GET /api/v1/tokens/me
     */
    @GetMapping("/me")
    public ApiResponse<TokenBalanceResponse> myBalance(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser
    ) {
        validateAuthenticated(authenticatedUser);

        return ApiResponse.onSuccess(
                tokenService.getMyBalance(authenticatedUser.getUserId())
        );
    }

    /*
     * 로그인한 사용자의 토큰 충전/차감 내역을 조회합니다.
     *
     * GET /api/v1/tokens/me/ledger?limit=30
     */
    @GetMapping("/me/ledger")
    public ApiResponse<List<TokenLedgerResponse>> myLedgers(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @RequestParam(required = false) Integer limit
    ) {
        validateAuthenticated(authenticatedUser);

        return ApiResponse.onSuccess(
                tokenService.getMyLedgers(authenticatedUser.getUserId(), limit)
        );
    }

    private void validateAuthenticated(AuthenticatedUser authenticatedUser) {
        if (authenticatedUser == null) {
            throw new AuthException(AuthErrorCode.UNAUTHORIZED_USER);
        }
    }
}