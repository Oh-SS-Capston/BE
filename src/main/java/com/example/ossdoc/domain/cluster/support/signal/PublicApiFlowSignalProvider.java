package com.example.ossdoc.domain.cluster.support.signal;

import com.example.ossdoc.domain.cluster.config.ClusterSignalProperties;
import com.example.ossdoc.domain.cluster.model.ProjectedEdge;
import com.example.ossdoc.domain.cluster.model.ProjectedNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 공개 API 진입점에서 BFS로 도달 가능한 노드들끼리 약한 결합을 주입한다. (refactplan.md 5순위)
 *
 * <p>동작 요약:
 * <ol>
 *   <li>{@code ProjectedNode.publicApi == true} 인 노드를 진입점으로 설정 (DB 재조회 없음)</li>
 *   <li>코드 엣지(synthetic edge 제외)를 기반으로 BFS (깊이 cap = {@code maxBfsDepth})</li>
 *   <li>같은 진입점 flow set 에 속하는 노드 쌍에 가상 엣지: 가중치 = baseWeight / (depth_i + depth_j)</li>
 *   <li>다중 진입점에서 중복되는 쌍은 최대 가중치(최소 깊이) 하나만 보존 — 가중치 누적 방지</li>
 *   <li>flow set 이 전체 노드의 80% 초과이면 그 진입점은 건너뜀 (과도 팽창 차단)</li>
 * </ol>
 *
 * <p>깊이 0 = 진입점 자체. 깊이 d 노드와 진입점(깊이 0) 쌍의 가중치 = baseWeight / d.
 * 두 노드가 모두 진입점(깊이 0)인 경우는 자기 쌍(a == b)이 걸러지므로 발생하지 않는다.
 *
 * <p>graphstore DB·{@code EdgeWeightPolicy} 는 일절 손대지 않고 메모리 ProjectedGraph 에만 주입한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PublicApiFlowSignalProvider implements SemanticSignalProvider {

    /** flow set 이 전체 노드 수의 이 비율을 초과하면 해당 진입점을 건너뛴다. */
    private static final double FLOW_SET_OVERLOAD_RATIO = 0.80;

    private final ClusterSignalProperties properties;

    @Override
    public String name() {
        return "API_FLOW";
    }

    @Override
    public boolean enabled() {
        return properties.getSignals().getApiFlow().isEnabled();
    }

    @Override
    public SignalResult provide(List<ProjectedNode> nodes,
                                Map<String, Integer> nodeIndexMap,
                                List<ProjectedEdge> codeEdges) {
        ClusterSignalProperties.ApiFlowSignal config = properties.getSignals().getApiFlow();
        double baseWeight = config.getBaseWeight();
        int maxBfsDepth = config.getMaxBfsDepth();
        int n = nodes.size();

        // 1. publicApi == true 인 노드를 진입점으로 수집 (nodes 는 symbolId 정렬 보장 → 인덱스도 결정적)
        List<Integer> entryIndices = new ArrayList<>();
        for (ProjectedNode node : nodes) {
            if (node.isPublicApi()) {
                Integer idx = nodeIndexMap.get(node.getSymbolId());
                if (idx != null) {
                    entryIndices.add(idx);
                }
            }
        }
        Collections.sort(entryIndices); // 재현성 확보

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("base_weight", baseWeight);
        meta.put("max_bfs_depth", maxBfsDepth);
        meta.put("entrypoint_count", entryIndices.size());

        if (entryIndices.isEmpty()) {
            meta.put("auto_disabled", true);
            meta.put("synthetic_edges", 0);
            log.warn("[CLUSTER-SIGNAL] API_FLOW: auto-disabled — no public API entry points found.");
            return new SignalResult(List.of(), meta);
        }

        // 2. 코드 엣지로 인접 리스트 구성 (무방향, synthetic edge 는 codeEdges 에 포함되지 않음)
        List<List<Integer>> adj = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            adj.add(new ArrayList<>());
        }
        for (ProjectedEdge edge : codeEdges) {
            int a = edge.getFromIndex();
            int b = edge.getToIndex();
            if (a >= 0 && a < n && b >= 0 && b < n && a != b) {
                adj.get(a).add(b);
                adj.get(b).add(a);
            }
        }

        // 3. 진입점별 BFS → flow set → 가상 엣지 생성
        // 쌍당 최대 가중치(최소 깊이 합) 보존: merge(key, w, Double::max)
        Map<String, Double> pairMaxWeight = new HashMap<>();
        int validFlowSets = 0;
        long totalFlowSetSize = 0L;

        for (int entryIdx : entryIndices) {
            int[] depth = new int[n];
            Arrays.fill(depth, -1);
            depth[entryIdx] = 0;

            Deque<Integer> queue = new ArrayDeque<>();
            queue.add(entryIdx);

            while (!queue.isEmpty()) {
                int cur = queue.poll();
                if (depth[cur] >= maxBfsDepth) {
                    continue;
                }
                for (int nb : adj.get(cur)) {
                    if (depth[nb] == -1) {
                        depth[nb] = depth[cur] + 1;
                        queue.add(nb);
                    }
                }
            }

            // 도달 가능한 노드 수집 (깊이 0 진입점 포함)
            List<Integer> flowSet = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (depth[i] >= 0) {
                    flowSet.add(i);
                }
            }

            // flow set 과도 팽창 차단: 전체 노드의 80% 초과이면 스킵
            if ((double) flowSet.size() / n > FLOW_SET_OVERLOAD_RATIO) {
                log.debug("[CLUSTER-SIGNAL] API_FLOW: entryIdx={} flow set size={} > {}% of n={}, skipped.",
                        entryIdx, flowSet.size(), (int) (FLOW_SET_OVERLOAD_RATIO * 100), n);
                continue;
            }

            validFlowSets++;
            totalFlowSetSize += flowSet.size();

            // 같은 flow set 안 노드 쌍에 가상 엣지
            // 가중치 = baseWeight / (depth_i + depth_j)
            // depth_i + depth_j >= 1 항상 성립 (쌍이 다른 두 노드이므로 둘 다 깊이 0 불가)
            for (int a = 0; a < flowSet.size(); a++) {
                for (int b = a + 1; b < flowSet.size(); b++) {
                    int idxA = flowSet.get(a);
                    int idxB = flowSet.get(b);
                    int dA = depth[idxA];
                    int dB = depth[idxB];
                    int depthSum = dA + dB;
                    if (depthSum == 0) {
                        continue; // 방어적 처리 — 실제로는 발생하지 않음
                    }
                    double w = baseWeight / depthSum;

                    int minIdx = Math.min(idxA, idxB);
                    int maxIdx = Math.max(idxA, idxB);
                    String key = minIdx + ":" + maxIdx;
                    pairMaxWeight.merge(key, w, Double::max);
                }
            }
        }

        // 4. 가상 엣지 목록으로 변환
        List<ProjectedEdge> edges = new ArrayList<>(pairMaxWeight.size());
        for (Map.Entry<String, Double> e : pairMaxWeight.entrySet()) {
            String[] s = e.getKey().split(":");
            edges.add(new ProjectedEdge(Integer.parseInt(s[0]), Integer.parseInt(s[1]), e.getValue()));
        }

        double avgFlowSetSize = validFlowSets == 0 ? 0.0 : (double) totalFlowSetSize / validFlowSets;

        meta.put("avg_flow_set_size", Math.round(avgFlowSetSize * 10.0) / 10.0);
        meta.put("synthetic_edges", edges.size());

        log.info("[CLUSTER-SIGNAL] API_FLOW: entries={}, validFlowSets={}, syntheticEdges={}",
                entryIndices.size(), validFlowSets, edges.size());

        return new SignalResult(edges, meta);
    }
}
