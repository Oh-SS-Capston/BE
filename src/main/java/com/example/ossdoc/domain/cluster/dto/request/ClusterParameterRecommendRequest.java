package com.example.ossdoc.domain.cluster.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 군집화/클래스맵 추천 파라미터 조회 요청.
 * - 프런트가 runId만 전달하면 백엔드가 그래프 크기를 읽어 기본 파라미터를 추천한다.
 */
@Getter
@NoArgsConstructor
public class ClusterParameterRecommendRequest {

    @NotBlank
    private String runId;
}
