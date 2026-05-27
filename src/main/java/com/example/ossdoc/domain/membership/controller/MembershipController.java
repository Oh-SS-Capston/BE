package com.example.ossdoc.domain.membership.controller;

import com.example.ossdoc.domain.auth.exception.AuthException;
import com.example.ossdoc.domain.auth.exception.code.AuthErrorCode;
import com.example.ossdoc.domain.membership.dto.response.MembershipStatusResponse;
import com.example.ossdoc.domain.membership.service.MembershipService;
import com.example.ossdoc.global.apiPayload.ApiResponse;
import com.example.ossdoc.global.security.jwt.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/membership")
public class MembershipController {

    private final MembershipService membershipService;

    @GetMapping("/me")
    public ApiResponse<MembershipStatusResponse> me(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser
    ) {
        if (authenticatedUser == null) {
            throw new AuthException(AuthErrorCode.AUTHENTICATION_REQUIRED);
        }

        return ApiResponse.onSuccess(
                membershipService.getMyMembership(authenticatedUser.getUserId())
        );
    }
}