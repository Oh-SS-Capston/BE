package com.example.ossdoc.domain.cluster.controller;

import com.example.ossdoc.domain.cluster.dto.request.ClusterBuildRequest;
import com.example.ossdoc.domain.cluster.dto.response.ClusterBuildResponse;
import com.example.ossdoc.domain.cluster.service.ClusterBuildService;
import com.example.ossdoc.global.apiPayload.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/clusters")
public class ClusterController {

    private final ClusterBuildService clusterBuildService;

    @PostMapping("/build")
    @Operation(summary = "graphstore 기반 subsystem / ranking 생성")
    public ApiResponse<ClusterBuildResponse> build(@Valid @RequestBody ClusterBuildRequest request) {
        return ApiResponse.onSuccess(clusterBuildService.build(request));
    }
}