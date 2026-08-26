package com.example.ossdoc.domain.auth.controller;

import com.example.ossdoc.domain.auth.dto.request.NicknameUpdateRequest;
import com.example.ossdoc.domain.auth.dto.response.CurrentUserResponse;
import com.example.ossdoc.domain.auth.dto.response.NicknameCheckResponse;
import com.example.ossdoc.domain.auth.service.AuthService;
import com.example.ossdoc.global.apiPayload.ApiResponse;
import com.example.ossdoc.global.security.jwt.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @GetMapping("/me")
    public ApiResponse<CurrentUserResponse> me(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser
    ) {
        return ApiResponse.onSuccess(authService.getCurrentUser(authenticatedUser));
    }

    @GetMapping("/nicknames/{nickname}/availability")
    public ApiResponse<NicknameCheckResponse> checkNickname(
            @PathVariable String nickname,
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser
    ) {
        return ApiResponse.onSuccess(
                authService.checkNickname(nickname, authenticatedUser)
        );
    }

    @PatchMapping("/me/nickname")
    public ApiResponse<CurrentUserResponse> updateNickname(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @Valid @RequestBody NicknameUpdateRequest request
    ) {
        return ApiResponse.onSuccess(
                authService.updateNickname(authenticatedUser, request)
        );
    }

    @PostMapping("/refresh")
    public ApiResponse<String> refresh(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        authService.refresh(request, response);
        return ApiResponse.onSuccess("토큰 재발급 완료");
    }

    @PostMapping("/logout")
    public ApiResponse<String> logout(
            HttpServletRequest request,
            HttpServletResponse response,
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser
    ) {
        authService.logout(request, response, authenticatedUser);
        return ApiResponse.onSuccess("로그아웃 완료");
    }

    @DeleteMapping("/me")
    public ApiResponse<String> deleteAccount(
            HttpServletRequest request,
            HttpServletResponse response,
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser
    ) {
        authService.deleteAccount(request, response, authenticatedUser);
        return ApiResponse.onSuccess("회원 탈퇴가 완료되었습니다.");
    }
}