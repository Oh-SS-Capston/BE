package com.example.ossdoc.domain.cluster.support.signal;

import com.example.ossdoc.domain.cluster.model.ProjectedEdge;
import com.example.ossdoc.domain.cluster.model.ProjectedNode;

import java.util.List;
import java.util.Map;

/**
 * 코드 엣지 외의 의미 신호를 메모리 가상 엣지(synthetic edge)로 공급하는 provider. (refactplan.md 2~5순위)
 *
 * <p>graphstore DB·{@code EdgeWeightPolicy} 는 일절 손대지 않고, 반환한 가상 엣지를
 * {@code GraphProjectionService} 의 undirectedWeightMap 에 합산하는 방식으로만 그래프를 보강한다.
 *
 * <p>구현체: {@link PackageSignalProvider}(2순위), {@link NameSimilaritySignalProvider}(3순위),
 * {@link DocCommentSignalProvider}(4순위), {@link PublicApiFlowSignalProvider}(5순위).
 */
public interface SemanticSignalProvider {

    /** 신호 식별자. "PACKAGE", "NAME", "DOC", "API_FLOW". 메타 키(소문자)로도 사용된다. */
    String name();

    /** 설정 토글. 통계적 신호이므로 기본 OFF, ablation 실험용으로 단계별 ON 한다. */
    boolean enabled();

    /**
     * 가상 엣지와 baseline 메타를 산출한다.
     *
     * @param nodes        투영 노드 목록(symbolId 정렬 보장)
     * @param nodeIndexMap symbolId → 노드 인덱스
     * @param codeEdges    augmentation 시작 전 코드 엣지 스냅샷 (5순위 BFS 용. 불필요한 provider 는 무시)
     * @return 가상 엣지 목록 + provider별 baseline 메타
     */
    SignalResult provide(List<ProjectedNode> nodes, Map<String, Integer> nodeIndexMap, List<ProjectedEdge> codeEdges);
}
