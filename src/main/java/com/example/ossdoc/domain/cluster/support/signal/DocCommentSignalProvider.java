package com.example.ossdoc.domain.cluster.support.signal;

import com.example.ossdoc.domain.cluster.config.ClusterSignalProperties;
import com.example.ossdoc.domain.cluster.model.ProjectedEdge;
import com.example.ossdoc.domain.cluster.model.ProjectedNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Javadoc 본문 TF-IDF 코사인 유사도로 의미적 결합을 강화한다. (refactplan.md 4순위)
 *
 * <p>동작 요약:
 * <ol>
 *   <li>{@code ProjectedNode.docTokens} 에 사전 투영된 토큰 리스트를 사용 (GraphProjectionService 에서 생성)</li>
 *   <li>corpus 전체로 TF-IDF 벡터 산출: TF = 문서 내 출현 빈도 / 전체 토큰 수, IDF = log((N+1)/(df+1))</li>
 *   <li>코사인 유사도 ≥ threshold 인 노드 쌍에 가상 엣지 (가중치 = baseWeight × cosine)</li>
 *   <li>doc 커버리지 &lt; 30% 이면 신호가 편향될 수 있어 자동 비활성화</li>
 * </ol>
 *
 * <p>graphstore DB·{@code EdgeWeightPolicy} 는 일절 손대지 않고 메모리 ProjectedGraph 에만 주입한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocCommentSignalProvider implements SemanticSignalProvider {

    /** doc이 있는 노드 비율이 이 값 미만이면 신호를 생략한다. */
    private static final double AUTO_DISABLE_DOC_RATIO = 0.30;

    private final ClusterSignalProperties properties;

    @Override
    public String name() {
        return "DOC";
    }

    @Override
    public boolean enabled() {
        return properties.getSignals().getDoc().isEnabled();
    }

    @Override
    public SignalResult provide(List<ProjectedNode> nodes, Map<String, Integer> nodeIndexMap, List<ProjectedEdge> codeEdges) {
        ClusterSignalProperties.DocSignal config = properties.getSignals().getDoc();
        double baseWeight = config.getBaseWeight();
        double cosineThreshold = config.getCosineThreshold();

        // 재현성: nodes 는 이미 symbolId 정렬(GraphProjectionService 보장) — stream filter 는 순서 유지.
        List<ProjectedNode> eligible = nodes.stream()
                .filter(n -> n.getDocTokens() != null && !n.getDocTokens().isEmpty())
                .toList();

        int totalNodes = nodes.size();
        int nodesWithDoc = eligible.size();
        double docRatio = totalNodes == 0 ? 0.0 : (double) nodesWithDoc / totalNodes;

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("base_weight", baseWeight);
        meta.put("cosine_threshold", cosineThreshold);
        meta.put("nodes_with_doc", nodesWithDoc);
        meta.put("nodes_without_doc", totalNodes - nodesWithDoc);
        meta.put("doc_coverage_ratio", Math.round(docRatio * 1000.0) / 1000.0);

        if (docRatio < AUTO_DISABLE_DOC_RATIO) {
            meta.put("auto_disabled", true);
            meta.put("synthetic_edges", 0);
            log.warn("[CLUSTER-SIGNAL] DOC: auto-disabled — doc coverage {}/{} < 30%. Skipping signal.",
                    nodesWithDoc, totalNodes);
            return new SignalResult(List.of(), meta);
        }

        int n = eligible.size();

        // document frequency: term → 해당 term 을 포함하는 문서(노드) 수
        Map<String, Integer> df = new HashMap<>();
        for (ProjectedNode node : eligible) {
            for (String term : new HashSet<>(node.getDocTokens())) {
                df.merge(term, 1, Integer::sum);
            }
        }

        // 노드별 TF-IDF 벡터 계산. IDF = log((N+1)/(df+1)) 로 항상 ≥ 0 보장.
        List<Map<String, Double>> vectors = new ArrayList<>(n);
        for (ProjectedNode node : eligible) {
            List<String> tokens = node.getDocTokens();
            int totalTerms = tokens.size();
            Map<String, Integer> tf = new HashMap<>();
            for (String t : tokens) tf.merge(t, 1, Integer::sum);

            Map<String, Double> vec = new HashMap<>();
            for (Map.Entry<String, Integer> e : tf.entrySet()) {
                double idf = Math.log((double) (n + 1) / (df.getOrDefault(e.getKey(), 0) + 1));
                double tfidf = ((double) e.getValue() / totalTerms) * idf;
                if (tfidf > 0.0) vec.put(e.getKey(), tfidf);
            }
            vectors.add(vec);
        }

        List<ProjectedEdge> edges = new ArrayList<>();
        double cosineSum = 0.0;

        for (int i = 0; i < eligible.size(); i++) {
            for (int j = i + 1; j < eligible.size(); j++) {
                double cosine = cosineSimilarity(vectors.get(i), vectors.get(j));
                if (cosine < cosineThreshold) continue;

                Integer fromIdx = nodeIndexMap.get(eligible.get(i).getSymbolId());
                Integer toIdx = nodeIndexMap.get(eligible.get(j).getSymbolId());
                if (fromIdx == null || toIdx == null || fromIdx.equals(toIdx)) continue;

                edges.add(new ProjectedEdge(fromIdx, toIdx, baseWeight * cosine));
                cosineSum += cosine;
            }
        }

        int edgeCount = edges.size();
        double avgCosine = edgeCount == 0 ? 0.0 : cosineSum / edgeCount;

        meta.put("synthetic_edges", edgeCount);
        meta.put("avg_cosine", Math.round(avgCosine * 1000.0) / 1000.0);

        log.info("[CLUSTER-SIGNAL] DOC: eligible={}, syntheticEdges={}, avgCosine={}",
                nodesWithDoc, edgeCount, Math.round(avgCosine * 1000.0) / 1000.0);

        return new SignalResult(edges, meta);
    }

    /** 두 TF-IDF 벡터 사이의 코사인 유사도 (A·B) / (|A|×|B|). */
    private double cosineSimilarity(Map<String, Double> a, Map<String, Double> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (Map.Entry<String, Double> e : a.entrySet()) {
            normA += e.getValue() * e.getValue();
            Double bv = b.get(e.getKey());
            if (bv != null) dot += e.getValue() * bv;
        }
        for (double v : b.values()) normB += v * v;
        return (normA == 0.0 || normB == 0.0) ? 0.0 : dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
