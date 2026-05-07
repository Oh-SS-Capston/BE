package com.example.ossdoc.domain.auth.controller;

import com.example.ossdoc.domain.auth.dto.response.CurrentUserResponse;
import com.example.ossdoc.domain.auth.service.AuthService;
import com.example.ossdoc.global.apiPayload.ApiResponse;
import com.example.ossdoc.global.security.jwt.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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

    @PostMapping("/refresh")
    public ApiResponse<String> refresh(HttpServletRequest request, HttpServletResponse response) {
        authService.refresh(request, response);
        return ApiResponse.onSuccess("토큰 재발급 완료");
    }

    @PostMapping("/logout")
    public ApiResponse<String> logout(HttpServletRequest request,
                                      HttpServletResponse response,
                                      @AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
        authService.logout(request, response, authenticatedUser);
        return ApiResponse.onSuccess("로그아웃 완료");
    }
}