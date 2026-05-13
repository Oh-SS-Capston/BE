package com.example.ossdoc.domain.publicapi.controller;

import com.example.ossdoc.domain.publicapi.dto.request.ApiMapBuildRequest;
import com.example.ossdoc.domain.publicapi.dto.response.ApiMapBuildResponse;
import com.example.ossdoc.domain.publicapi.service.ApiMapBuildService;
import com.example.ossdoc.global.apiPayload.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/apimap")
public class ApiMapController {

    private final ApiMapBuildService apiMapBuildService;

    @PostMapping("/build")
    @Operation(summary = "공개 API 진입점·확장 포인트 분석 및 api_surface.json / api_map.json 생성")
    public ApiResponse<ApiMapBuildResponse> build(@Valid @RequestBody ApiMapBuildRequest request) {
        return ApiResponse.onSuccess(apiMapBuildService.build(request.getRunId()));
    }
}
