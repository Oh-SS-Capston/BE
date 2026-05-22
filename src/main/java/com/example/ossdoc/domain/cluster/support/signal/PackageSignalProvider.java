package com.example.ossdoc.domain.cluster.support.signal;

import com.example.ossdoc.domain.cluster.config.ClusterSignalProperties;
import com.example.ossdoc.domain.cluster.model.ProjectedEdge;
import com.example.ossdoc.domain.cluster.model.ProjectedNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 같은 패키지에 속한 타입 사이의 약한 의미 결합을 가상 엣지로 주입한다. (refactplan.md 2순위)
 *
 * <p>패키지 크기에 따라 토폴로지를 달리해 엣지 폭발과 과결합을 동시에 막는다.
 * <ul>
 *   <li>크기 &lt; starTopologyFrom : 완전 그래프 (N(N-1)/2 엣지)</li>
 *   <li>starTopologyFrom ≤ 크기 ≤ skipFrom : star 토폴로지 (허브 1개에 나머지 연결)</li>
 *   <li>크기 &gt; skipFrom : 신호 차단 (루트 패키지/과도 결합 방지)</li>
 * </ul>
 *
 * <p>가중치는 {@code baseWeight / log2(packageSize + 1)} 로 큰 패키지를 자동 감쇠한다.
 * graphstore DB·{@code EdgeWeightPolicy} 는 일절 손대지 않고 메모리 ProjectedGraph 에만 주입한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PackageSignalProvider implements SemanticSignalProvider {

    /** 허브 선택 3순위: 패키지 마지막 토큰과 simpleName 토큰의 자카드 임계값. */
    private static final double HUB_NAME_JACCARD_THRESHOLD = 0.5;

    private final ClusterSignalProperties properties;

    @Override
    public String name() {
        return "PACKAGE";
    }

    @Override
    public boolean enabled() {
        return properties.getSignals().getPackage().isEnabled();
    }

    @Override
    public SignalResult provide(List<ProjectedNode> nodes, Map<String, Integer> nodeIndexMap, List<ProjectedEdge> codeEdges) {
        ClusterSignalProperties.PackageSignal config = properties.getSignals().getPackage();
        double baseWeight = config.getBaseWeight();
        int starTopologyFrom = config.getStarTopologyFrom();
        int skipFrom = config.getSkipFrom();

        // 패키지명 기준 그룹핑 — 재현성을 위해 TreeMap(패키지키 정렬) + 멤버 symbolId 정렬을 사용한다.
        Map<String, List<ProjectedNode>> byPackage = new TreeMap<>();
        for (ProjectedNode node : nodes) {
            String pkg = node.getPackageName();
            if (pkg == null || pkg.isBlank()) {
                continue; // 패키지 미상 노드는 신호 대상에서 제외
            }
            byPackage.computeIfAbsent(pkg, k -> new ArrayList<>()).add(node);
        }

        List<ProjectedEdge> edges = new ArrayList<>();
        int skippedOversized = 0;

        for (Map.Entry<String, List<ProjectedNode>> entry : byPackage.entrySet()) {
            String packageName = entry.getKey();
            List<ProjectedNode> members = new ArrayList<>(entry.getValue());
            members.sort(Comparator.comparing(ProjectedNode::getSymbolId));

            int size = members.size();
            if (size < 2) {
                continue; // 단일 멤버 패키지는 엣지가 발생하지 않음
            }

            if (size > skipFrom) {
                // 루트 패키지 또는 너무 넓은 의도 — 신호 차단
                skippedOversized++;
                continue;
            }

            // 패키지가 클수록 가중치를 감쇠해 과결합을 막는다.
            double weight = baseWeight / log2(size + 1);

            if (size < starTopologyFrom) {
                // 완전 그래프: 패키지 내 모든 노드 쌍을 연결
                for (int i = 0; i < size; i++) {
                    for (int j = i + 1; j < size; j++) {
                        addEdge(edges, nodeIndexMap, members.get(i), members.get(j), weight);
                    }
                }
            } else {
                // star 토폴로지: 허브 1개에만 나머지를 연결해 엣지 폭발을 막는다.
                ProjectedNode hub = selectHub(members, packageName);
                for (ProjectedNode member : members) {
                    if (member.getSymbolId().equals(hub.getSymbolId())) {
                        continue;
                    }
                    addEdge(edges, nodeIndexMap, hub, member, weight);
                }
            }
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("base_weight", baseWeight);
        meta.put("synthetic_edges", edges.size());
        meta.put("package_count", byPackage.size());
        meta.put("skipped_oversized", skippedOversized);

        log.info("[CLUSTER-SIGNAL] PACKAGE: packages={}, syntheticEdges={}, skippedOversized={}",
                byPackage.size(), edges.size(), skippedOversized);

        return new SignalResult(edges, meta);
    }

    /**
     * 노드 인덱스를 조회해 가상 엣지를 추가한다. 자기 루프/미투영 노드는 건너뛴다.
     */
    private void addEdge(List<ProjectedEdge> edges, Map<String, Integer> nodeIndexMap,
                         ProjectedNode a, ProjectedNode b, double weight) {
        Integer fromIndex = nodeIndexMap.get(a.getSymbolId());
        Integer toIndex = nodeIndexMap.get(b.getSymbolId());
        if (fromIndex == null || toIndex == null || fromIndex.equals(toIndex)) {
            return;
        }
        edges.add(new ProjectedEdge(fromIndex, toIndex, weight));
    }

    /**
     * star 토폴로지의 허브를 cascade 우선순위로 결정한다.
     * <ol>
     *   <li>publicApi == true (공식 진입점)</li>
     *   <li>evidenceCount 최대 (가장 많이 참조되는 노드)</li>
     *   <li>simpleName 토큰이 패키지 마지막 토큰과 자카드 ≥ 0.5</li>
     *   <li>symbolId 정렬상 첫 번째 (결정적 fallback)</li>
     * </ol>
     * 단계별 fallback 이라 항상 정확히 1개가 선택되며, 같은 입력 → 같은 허브가 보장된다.
     */
    private ProjectedNode selectHub(List<ProjectedNode> members, String packageName) {
        // 1순위: 공식 진입점
        List<ProjectedNode> candidates = members.stream()
                .filter(ProjectedNode::isPublicApi)
                .toList();
        if (candidates.isEmpty()) {
            candidates = members;
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }

        // 2순위: evidenceCount 최대
        int maxEvidence = candidates.stream()
                .mapToInt(ProjectedNode::getEvidenceCount)
                .max()
                .orElse(0);
        List<ProjectedNode> topEvidence = candidates.stream()
                .filter(n -> n.getEvidenceCount() == maxEvidence)
                .toList();
        if (topEvidence.size() == 1) {
            return topEvidence.get(0);
        }

        // 3순위: simpleName 토큰이 패키지 마지막 토큰과 자카드 ≥ 0.5
        String lastToken = lastPackageToken(packageName);
        List<ProjectedNode> nameMatched = topEvidence.stream()
                .filter(n -> packageTokenJaccard(n.getSimpleName(), lastToken) >= HUB_NAME_JACCARD_THRESHOLD)
                .toList();
        List<ProjectedNode> narrowed = nameMatched.isEmpty() ? topEvidence : nameMatched;
        if (narrowed.size() == 1) {
            return narrowed.get(0);
        }

        // 4순위: symbolId 정렬상 첫 번째 (결정적 fallback)
        return narrowed.stream()
                .min(Comparator.comparing(ProjectedNode::getSymbolId))
                .orElse(narrowed.get(0));
    }

    /**
     * 패키지명의 마지막 세그먼트를 소문자로 반환한다. 예) org.apache.commons.cli.help → "help"
     */
    private String lastPackageToken(String packageName) {
        if (packageName == null || packageName.isBlank()) {
            return "";
        }
        String[] parts = packageName.split("\\.");
        return parts[parts.length - 1].toLowerCase(Locale.ROOT);
    }

    /**
     * simpleName 의 CamelCase 토큰 집합과 패키지 마지막 토큰 {@code {lastToken}} 사이 자카드 유사도.
     * 예) "HelpFormatter" {help, formatter} vs "help" → 1/2 = 0.5
     */
    private double packageTokenJaccard(String simpleName, String lastToken) {
        if (simpleName == null || simpleName.isBlank() || lastToken == null || lastToken.isBlank()) {
            return 0.0;
        }
        Set<String> nameTokens = camelCaseTokens(simpleName);
        if (nameTokens.isEmpty()) {
            return 0.0;
        }
        Set<String> union = new HashSet<>(nameTokens);
        union.add(lastToken);
        int intersection = nameTokens.contains(lastToken) ? 1 : 0;
        return (double) intersection / union.size();
    }

    /**
     * CamelCase 식별자를 소문자 토큰 집합으로 분해한다. 예) "HelpFormatter" → {help, formatter}
     */
    private Set<String> camelCaseTokens(String simpleName) {
        Set<String> tokens = new HashSet<>();
        for (String token : simpleName.split("(?<!^)(?=[A-Z])")) {
            if (!token.isBlank()) {
                tokens.add(token.toLowerCase(Locale.ROOT));
            }
        }
        return tokens;
    }

    private double log2(int value) {
        return Math.log(value) / Math.log(2);
    }
}
