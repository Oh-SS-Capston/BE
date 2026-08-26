package com.example.ossdoc.domain.cluster.support.signal;

import com.example.ossdoc.domain.cluster.model.ProjectedEdge;

import java.util.List;
import java.util.Map;

/**
 * {@link SemanticSignalProvider} 1회 실행 결과.
 *
 * <p>{@code edges} 는 undirectedWeightMap 에 합산할 가상 엣지,
 * {@code meta} 는 산출물 {@code algorithm.signals.<name>} 에 기록할 baseline 지표다.
 * (refine 도메인의 {@code RefinementResult} 와 동일한 패턴)
 */
public record SignalResult(List<ProjectedEdge> edges, Map<String, Object> meta) {
}
