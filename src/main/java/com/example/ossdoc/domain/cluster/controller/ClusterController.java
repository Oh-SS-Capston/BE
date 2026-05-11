package com.example.ossdoc.domain.cluster.controller;

import com.example.ossdoc.domain.cluster.dto.request.ClusterBuildRequest;
import com.example.ossdoc.domain.cluster.dto.request.ClusterParameterRecommendRequest;
import com.example.ossdoc.domain.cluster.dto.response.ClusterBuildResponse;
import com.example.ossdoc.domain.cluster.dto.response.ClusterParameterRecommendResponse;
import com.example.ossdoc.domain.cluster.service.ClusterBuildService;
import com.example.ossdoc.domain.cluster.service.ClusterParameterRecommendService;
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
@RequestMapping("/api/v1/clusters")
public class ClusterController {

    private final ClusterBuildService clusterBuildService;
    private final ClusterParameterRecommendService clusterParameterRecommendService;

    @PostMapping("/build")
    @Operation(summary = "graphstore 기반 subsystem / ranking 생성")
    public ApiResponse<ClusterBuildResponse> build(@Valid @RequestBody ClusterBuildRequest request) {
        return ApiResponse.onSuccess(clusterBuildService.build(request));
    }

    /**
     * run 그래프 규모를 기준으로 군집화/클래스맵 추천 파라미터를 반환한다.
     * - 프런트는 응답값을 기본 입력값으로 채운 뒤, 필요하면 수동으로 조정한다.
     */
    @PostMapping("/recommend")
    @Operation(summary = "run 규모 기반 cluster/class-map 추천 파라미터 조회")
    public ApiResponse<ClusterParameterRecommendResponse> recommend(
            @Valid @RequestBody ClusterParameterRecommendRequest request
    ) {
        return ApiResponse.onSuccess(clusterParameterRecommendService.recommend(request));
    }
}
