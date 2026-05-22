package com.example.ossdoc.domain.cluster.support.signal;

import com.example.ossdoc.domain.cluster.model.ProjectedEdge;
import com.example.ossdoc.domain.cluster.model.ProjectedNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 활성화된 모든 {@link SemanticSignalProvider} 를 실행해 가상 엣지를 코드 엣지와 같은
 * weight map 에 합산하는 조립기. (refactplan.md 2~5순위 진입점)
 *
 * <p>provider 1건의 실패가 군집화 자체를 막지 않도록 provider 단위로 예외를 격리한다
 * ({@code SubsystemRefinerService} 와 동일한 정책). 산출물 비교를 위해 provider별 baseline 메타를
 * 모아 {@code algorithm.signals} 로 반환한다.
 */
@Slf4j
@Component
public class SemanticSignalAugmenter {

    /** 메타 출력 순서를 결정적으로 만들기 위해 provider 를 name 기준 정렬해 보관한다. */
    private final List<SemanticSignalProvider> providers;

    public SemanticSignalAugmenter(List<SemanticSignalProvider> providers) {
        this.providers = providers.stream()
                .sorted(Comparator.comparing(SemanticSignalProvider::name))
                .toList();
    }

    /**
     * 활성 provider 의 가상 엣지를 {@code undirectedWeightMap} 에 직접 합산한다.
     *
     * @param nodes               투영 노드 목록
     * @param nodeIndexMap        symbolId → 노드 인덱스
     * @param undirectedWeightMap 코드 엣지가 이미 누적된 weight map ("min:max" → weight). 이 메서드가 변경한다.
     * @return provider별 baseline 메타 ({@code algorithm.signals} 에 그대로 기록)
     */
    public Map<String, Object> augment(List<ProjectedNode> nodes,
                                       Map<String, Integer> nodeIndexMap,
                                       Map<String, Double> undirectedWeightMap) {
        Map<String, Object> signalsMeta = new LinkedHashMap<>();

        // 5순위 PublicApiFlowSignalProvider BFS 용: augmentation 시작 전 코드 엣지만 스냅샷.
        // 이후 provider 들이 undirectedWeightMap 에 synthetic edge 를 추가하므로 미리 캡처한다.
        List<ProjectedEdge> codeEdges = buildCodeEdgeSnapshot(undirectedWeightMap);

        for (SemanticSignalProvider provider : providers) {
            String key = provider.name().toLowerCase();

            if (!provider.enabled()) {
                signalsMeta.put(key, Map.of("enabled", false));
                continue;
            }

            try {
                SignalResult result = provider.provide(nodes, nodeIndexMap, codeEdges);
                int merged = mergeEdges(result.edges(), undirectedWeightMap);

                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("enabled", true);
                meta.putAll(result.meta());
                signalsMeta.put(key, meta);

                log.info("[CLUSTER-SIGNAL] provider {} merged {} synthetic edges into weight map.",
                        provider.name(), merged);
            } catch (Exception e) {
                // 신호 provider 1건의 실패가 군집화 산출물 생성을 막지 않도록 격리한다.
                log.error("[CLUSTER-SIGNAL] provider {} failed. skipping this signal.", provider.name(), e);
                signalsMeta.put(key, Map.of("enabled", true, "error", e.getClass().getSimpleName()));
            }
        }

        return signalsMeta;
    }

    /**
     * undirectedWeightMap 의 현재 상태를 ProjectedEdge 리스트로 복사한다.
     * augmentation 시작 전에 호출해 코드 엣지만 포함된 스냅샷을 확보한다.
     */
    private List<ProjectedEdge> buildCodeEdgeSnapshot(Map<String, Double> weightMap) {
        List<ProjectedEdge> snapshot = new ArrayList<>(weightMap.size());
        for (Map.Entry<String, Double> e : weightMap.entrySet()) {
            String[] s = e.getKey().split(":");
            snapshot.add(new ProjectedEdge(Integer.parseInt(s[0]), Integer.parseInt(s[1]), e.getValue()));
        }
        return snapshot;
    }

    /**
     * 가상 엣지를 코드 엣지와 동일한 "min:max" 키 규칙으로 weight map 에 누적 합산한다.
     *
     * @return 실제 합산된 엣지 수
     */
    private int mergeEdges(List<ProjectedEdge> edges, Map<String, Double> undirectedWeightMap) {
        int merged = 0;
        for (ProjectedEdge edge : edges) {
            int a = Math.min(edge.getFromIndex(), edge.getToIndex());
            int b = Math.max(edge.getFromIndex(), edge.getToIndex());
            if (a == b) {
                continue;
            }
            undirectedWeightMap.merge(a + ":" + b, edge.getWeight(), Double::sum);
            merged++;
        }
        return merged;
    }
}
