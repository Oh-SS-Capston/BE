package com.example.ossdoc.domain.cluster.model;

import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Map;

@Getter
@Builder
public class ProjectedGraph {
    private String runId;
    private List<ProjectedNode> nodes;
    private List<ProjectedEdge> edges;

    private Map<String, Integer> nodeIndexMap;

    // refactplan.md 2~5순위: 가상 엣지 신호 provider별 baseline 메타 (algorithm.signals 로 기록)
    private Map<String, Object> signalMeta;
}
