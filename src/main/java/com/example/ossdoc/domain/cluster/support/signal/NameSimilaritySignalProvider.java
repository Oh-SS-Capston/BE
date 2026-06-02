package com.example.ossdoc.domain.cluster.support.signal;

import com.example.ossdoc.domain.cluster.config.ClusterSignalProperties;
import com.example.ossdoc.domain.cluster.model.ProjectedEdge;
import com.example.ossdoc.domain.cluster.model.ProjectedNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * simpleName 의 CamelCase 토큰 자카드 유사도로 같은 역할/도메인 클래스를 약하게 결합한다. (refactplan.md 3순위)
 *
 * <p>동작 요약:
 * <ol>
 *   <li>simpleName 을 CamelCase 토큰화 ({@code HelpFormatter} → {@code {help, formatter}})</li>
 *   <li>역할 접미사·범용 prefix 성격의 stopword 제거 ({@code builder, exception} 등)</li>
 *   <li>토큰 수 &lt; 2 인 노드는 제외 (단일 토큰은 자카드 극단값 → 노이즈)</li>
 *   <li>자카드 유사도 ≥ threshold 인 노드 쌍에 가상 엣지 (가중치 = baseWeight × jaccard)</li>
 * </ol>
 *
 * <p>stopword 기준: "역할 접미사·범용 prefix" 에 한정. 분석 대상 프로젝트의 도메인 핵심 단어({@code help},
 * {@code option}, {@code parser} 등)는 stopword 에 포함하지 않는다.
 * graphstore DB·{@code EdgeWeightPolicy} 는 일절 손대지 않고 메모리 ProjectedGraph 에만 주입한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NameSimilaritySignalProvider implements SemanticSignalProvider {

    /**
     * 역할 접미사·범용 prefix 목록. 도메인 핵심 단어(Option, Parser, Help 등)는 포함하지 않는다.
     * 메타 출력 시 정렬된 원본 표기(PascalCase)를 보여주기 위해 Set 과 별도로 보관한다.
     */
    private static final List<String> STOPWORD_DISPLAY = List.of(
            "Abstract", "Builder", "Default", "Exception", "Factory", "Helper", "Impl", "Util"
    );
    /** 소문자 변환 후 비교용 stopword 집합. */
    private static final Set<String> STOPWORDS = new HashSet<>(
            STOPWORD_DISPLAY.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList()
    );

    private final ClusterSignalProperties properties;

    @Override
    public String name() {
        return "NAME";
    }

    @Override
    public boolean enabled() {
        return properties.getSignals().getName().isEnabled();
    }

    @Override
    public SignalResult provide(List<ProjectedNode> nodes, Map<String, Integer> nodeIndexMap, List<ProjectedEdge> codeEdges) {
        ClusterSignalProperties.NameSignal config = properties.getSignals().getName();
        double baseWeight = config.getBaseWeight();
        double jaccardThreshold = config.getJaccardThreshold();

        // 재현성을 위해 nodes 는 이미 symbolId 정렬(GraphProjectionService 보장) — 별도 정렬 불필요.
        // 필터링된 토큰 집합을 미리 계산해 O(N^2) 루프에서 토큰화 반복을 막는다.
        List<ProjectedNode> eligible = new ArrayList<>();
        List<Set<String>> tokenSets = new ArrayList<>();
        for (ProjectedNode node : nodes) {
            Set<String> tokens = filteredTokens(node.getSimpleName());
            if (tokens.size() >= 2) {
                eligible.add(node);
                tokenSets.add(tokens);
            }
        }

        List<ProjectedEdge> edges = new ArrayList<>();
        double jaccardSum = 0.0;

        for (int i = 0; i < eligible.size(); i++) {
            for (int j = i + 1; j < eligible.size(); j++) {
                double jaccard = jaccardSimilarity(tokenSets.get(i), tokenSets.get(j));
                if (jaccard < jaccardThreshold) {
                    continue;
                }

                Integer fromIndex = nodeIndexMap.get(eligible.get(i).getSymbolId());
                Integer toIndex = nodeIndexMap.get(eligible.get(j).getSymbolId());
                if (fromIndex == null || toIndex == null || fromIndex.equals(toIndex)) {
                    continue;
                }

                double weight = baseWeight * jaccard;
                edges.add(new ProjectedEdge(fromIndex, toIndex, weight));
                jaccardSum += jaccard;
            }
        }

        int edgeCount = edges.size();
        double avgJaccard = edgeCount == 0 ? 0.0 : jaccardSum / edgeCount;

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("base_weight", baseWeight);
        meta.put("jaccard_threshold", jaccardThreshold);
        meta.put("stopwords", STOPWORD_DISPLAY);
        meta.put("eligible_nodes", eligible.size());
        meta.put("synthetic_edges", edgeCount);
        meta.put("avg_jaccard", Math.round(avgJaccard * 1000.0) / 1000.0);

        log.info("[CLUSTER-SIGNAL] NAME: eligible={}, syntheticEdges={}, avgJaccard={}",
                eligible.size(), edgeCount, avgJaccard);

        return new SignalResult(edges, meta);
    }

    /**
     * CamelCase simpleName 을 소문자 토큰으로 분해하고 stopword 를 제거한다.
     * 삽입 순서를 유지해 재현성을 확보한다.
     */
    private Set<String> filteredTokens(String simpleName) {
        if (simpleName == null || simpleName.isBlank()) {
            return Set.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : simpleName.split("(?<!^)(?=[A-Z])")) {
            if (!token.isBlank()) {
                String lower = token.toLowerCase(Locale.ROOT);
                if (!STOPWORDS.contains(lower)) {
                    tokens.add(lower);
                }
            }
        }
        return tokens;
    }

    /** 두 토큰 집합 사이의 자카드 유사도 |A ∩ B| / |A ∪ B|. */
    private double jaccardSimilarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }
}
