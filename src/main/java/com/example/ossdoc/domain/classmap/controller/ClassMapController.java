// 역할: Public API 중심 클래스 맵 빌드 API 엔드포인트를 제공한다.
package com.example.ossdoc.domain.classmap.controller;

import com.example.ossdoc.domain.auth.exception.AuthException;
import com.example.ossdoc.domain.auth.exception.code.AuthErrorCode;
import com.example.ossdoc.domain.classmap.dto.request.ClassMapBuildRequest;
import com.example.ossdoc.domain.classmap.dto.response.ClassMapBuildResponse;
import com.example.ossdoc.domain.classmap.service.ClassMapBuildService;
import com.example.ossdoc.global.apiPayload.ApiResponse;
import com.example.ossdoc.global.security.jwt.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/class-map")
public class ClassMapController {
    private final ClassMapBuildService classMapBuildService;

    @PostMapping("/build")
    public ApiResponse<ClassMapBuildResponse> build(
            @Valid @RequestBody ClassMapBuildRequest request,
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser
    ) {
        if (authenticatedUser == null) {
            throw new AuthException(AuthErrorCode.UNAUTHORIZED_USER);
        }
        ClassMapBuildResponse response = classMapBuildService.build(request, authenticatedUser.getUserId());
        return ApiResponse.onSuccess(response);
    }
}
