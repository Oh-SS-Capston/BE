package com.example.ossdoc.domain.build.controller;

import com.example.ossdoc.domain.build.dto.request.BuildResolveRequest;
import com.example.ossdoc.domain.build.dto.response.BuildResolveResponse;
import com.example.ossdoc.domain.build.service.BuildResolveService;
import com.example.ossdoc.global.apiPayload.ApiResponse; // <- 너희 프로젝트 실제 ApiResponse 경로로 맞춰
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/build")
public class BuildController {

    private final BuildResolveService buildResolveService;

    @PostMapping("/resolve")
    public ApiResponse<BuildResolveResponse> resolve(@RequestBody BuildResolveRequest request) {
        BuildResolveResponse response = buildResolveService.resolve(request.getRunId());
        return ApiResponse.onSuccess(response);
    }
}
