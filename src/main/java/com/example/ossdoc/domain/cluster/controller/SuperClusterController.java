package com.example.ossdoc.domain.cluster.controller;

import com.example.ossdoc.domain.cluster.dto.request.SuperClusterBuildRequest;
import com.example.ossdoc.domain.cluster.dto.response.SuperClusterBuildResponse;
import com.example.ossdoc.domain.cluster.service.SuperClusterBuildService;
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
@RequestMapping("/api/v1/super-clusters")
public class SuperClusterController {

    private final SuperClusterBuildService superClusterBuildService;

    @PostMapping("/build")
    @Operation(summary = "level-1 subsystem을 super-cluster로 병합 (superCluster.enabled=true 필요)")
    public ApiResponse<SuperClusterBuildResponse> build(@Valid @RequestBody SuperClusterBuildRequest request) {
        return ApiResponse.onSuccess(superClusterBuildService.build(request));
    }
}
