package com.example.ossdoc.domain.cluster.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * 오픈소스 크기(그래프 규모)에 맞춘 군집화/클래스맵 추천값 응답.
 * - 프런트는 이 값을 초기 폼 기본값으로 채우고, 사용자가 필요 시 수동 조정한다.
 */
@Getter
@Builder
@AllArgsConstructor
public class ClusterParameterRecommendResponse {

    private String runId;

    /**
     * SMALL / MEDIUM / LARGE
     */
    private String sizeTier;

    /**
     * TYPE 심볼 개수 (cluster/class-map 기준 핵심 노드 규모)
     */
    private long typeSymbolCount;

    /**
     * run 전체 edge 개수 (밀도 보정용)
     */
    private long edgeCount;

    /**
     * edgeCount / typeSymbolCount
     */
    private double edgePerType;

    private ClusterRecommended clusterRecommended;

    private ClassMapRecommended classMapRecommended;

    @Getter
    @Builder
    @AllArgsConstructor
    public static class ClusterRecommended {
        private double resolution;
        private int iterations;
        private int minClusterSize;
        private int topK;
    }

    @Getter
    @Builder
    @AllArgsConstructor
    public static class ClassMapRecommended {
        private int maxNodes;
        private int maxEdges;
        private int startHereTopN;
    }
}
