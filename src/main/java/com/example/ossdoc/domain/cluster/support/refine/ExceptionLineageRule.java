package com.example.ossdoc.domain.cluster.support.refine;

import com.example.ossdoc.domain.cluster.config.ClusterSignalProperties;
import com.example.ossdoc.domain.cluster.model.ProjectedNode;
import com.example.ossdoc.domain.cluster.model.subsystem.Subsystem;
import com.example.ossdoc.domain.graphstore.entity.Edge;
import com.example.ossdoc.domain.graphstore.enums.EdgeType;
import com.example.ossdoc.domain.graphstore.repository.EdgeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * EXTENDS 계보로 연결된 예외 클래스들을 단일 subsystem 으로 통합한다.
 *
 * <p>예외 계층은 단일 책임이라 Leiden 분포보다 계보를 우선한다.
 * commons-cli 의 {@code ParseException}, {@code MissingArgumentException} 등이
 * 여러 클러스터로 흩어지는 문제를 바로잡는다.
 *
 * <p>데이터 출처: graphstore EdgeRepository 의 RESOLVED EXTENDS edge 만 직접 조회한다.
 * <ul>
 *   <li>ProjectedEdge 는 EdgeType 정보가 없어 synthetic edge 와 섞일 위험이 있다 → 사용 불가.</li>
 *   <li>외부 라이브러리 클래스(미해결)까지는 추적하지 않는다(RESOLVED 까지만).
 *       예외 여부는 계보 구성원의 simpleName 으로 판별한다.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExceptionLineageRule implements SubsystemRefinementRule {

    /** SubsystemAssembler 가 작은 클러스터를 모아 부여하는 라벨. */
    private static final String MISC_LABEL = "misc";

    private final ClusterSignalProperties properties;
    private final EdgeRepository edgeRepository;
    private final SubsystemReassembler reassembler;

    @Override
    public String name() {
        return "EXCEPTION";
    }

    @Override
    public boolean enabled() {
        return properties.getRefiner().getRules().getException().isEnabled();
    }

    @Override
    public RefinementResult apply(List<Subsystem> subsystems, List<ProjectedNode> nodes, String runId) {
        Map<String, ProjectedNode> nodeById = new HashMap<>();
        for (ProjectedNode node : nodes) {
            if (node.getSymbolId() != null) {
                nodeById.put(node.getSymbolId(), node);
            }
        }

        // RESOLVED EXTENDS edge 로 in-graph child→parent / 무방향 인접 맵을 구성한다.
        Map<String, String> parentOf = new HashMap<>();
        Map<String, Set<String>> adjacency = new HashMap<>();
        for (Edge edge : edgeRepository.findAllByRun_RunIdAndEdgeTypeAndToSymbolIsNotNull(runId, EdgeType.EXTENDS)) {
            if (edge.getFromSymbol() == null || edge.getToSymbol() == null) {
                continue;
            }
            String child = edge.getFromSymbol().getSymbolId();
            String parent = edge.getToSymbol().getSymbolId();
            if (child == null || parent == null || child.equals(parent)) {
                continue;
            }
            if (!nodeById.containsKey(child) || !nodeById.containsKey(parent)) {
                continue; // test source 등으로 그래프에서 제외된 심볼은 무시한다.
            }
            parentOf.putIfAbsent(child, parent);
            adjacency.computeIfAbsent(child, k -> new HashSet<>()).add(parent);
            adjacency.computeIfAbsent(parent, k -> new HashSet<>()).add(child);
        }

        List<List<String>> components = connectedComponents(adjacency);

        List<String> orderedIds = new ArrayList<>(subsystems.stream().map(Subsystem::getSubsystemId).toList());
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, String> placement = new HashMap<>();
        Set<String> miscSubsystemIds = new HashSet<>();
        for (Subsystem subsystem : subsystems) {
            names.put(subsystem.getSubsystemId(), subsystem.getName());
            if (MISC_LABEL.equals(subsystem.getName())) {
                miscSubsystemIds.add(subsystem.getSubsystemId());
            }
            for (String member : subsystem.getMemberSymbolIds()) {
                placement.put(member, subsystem.getSubsystemId());
            }
        }

        int mergedCount = 0;
        List<String> lineageRoots = new ArrayList<>();
        int sequence = 1;

        for (List<String> component : components) {
            if (component.size() < 2) {
                continue; // 단독 노드는 "통합"의 의미가 없다.
            }
            if (!isExceptionFamily(component, nodeById)) {
                continue;
            }

            // 계보가 이미 단일 named subsystem 에 모여 있으면 보정 불필요.
            Set<String> currentSubsystems = new HashSet<>();
            for (String member : component) {
                String subsystemId = placement.get(member);
                if (subsystemId != null) {
                    currentSubsystems.add(subsystemId);
                }
            }
            boolean alreadyConsolidated = currentSubsystems.size() == 1
                    && !miscSubsystemIds.contains(currentSubsystems.iterator().next());
            if (alreadyConsolidated) {
                continue;
            }

            String rootName = lineageRootName(component, parentOf, nodeById);
            String newSubsystemId = String.format("ss_exc_%03d", sequence++);
            names.put(newSubsystemId, rootName);
            orderedIds.add(newSubsystemId);
            for (String member : component) {
                placement.put(member, newSubsystemId);
            }
            mergedCount += component.size();
            lineageRoots.add(rootName);
        }

        List<Subsystem> refined = reassembler.reassemble(orderedIds, names, placement, nodeById);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("merged_count", mergedCount);
        meta.put("lineage_roots", lineageRoots);

        log.info("[CLUSTER-REFINER] EXCEPTION rule. mergedCount={}, lineageRoots={}", mergedCount, lineageRoots);

        return new RefinementResult(refined, meta);
    }

    /**
     * 무방향 인접 맵을 BFS 로 순회해 연결 요소를 추출한다.
     * seed 를 정렬 순서로 처리해 요소 순서를 재현 가능하게 만든다.
     */
    private List<List<String>> connectedComponents(Map<String, Set<String>> adjacency) {
        List<List<String>> components = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        for (String seed : adjacency.keySet().stream().sorted().toList()) {
            if (visited.contains(seed)) {
                continue;
            }
            List<String> component = new ArrayList<>();
            Deque<String> queue = new ArrayDeque<>();
            queue.add(seed);
            visited.add(seed);
            while (!queue.isEmpty()) {
                String current = queue.poll();
                component.add(current);
                for (String neighbor : adjacency.getOrDefault(current, Set.of())) {
                    if (visited.add(neighbor)) {
                        queue.add(neighbor);
                    }
                }
            }
            component.sort(null);
            components.add(component);
        }
        return components;
    }

    /**
     * 계보 구성원 중 예외 명명 규칙(*Exception, *Error, Throwable)에 맞는 노드가 하나라도 있으면
     * 예외 계보로 본다.
     */
    private boolean isExceptionFamily(List<String> component, Map<String, ProjectedNode> nodeById) {
        for (String symbolId : component) {
            ProjectedNode node = nodeById.get(symbolId);
            if (node != null && isExceptionName(node.getSimpleName())) {
                return true;
            }
        }
        return false;
    }

    private boolean isExceptionName(String simpleName) {
        if (simpleName == null) {
            return false;
        }
        return simpleName.endsWith("Exception")
                || simpleName.endsWith("Error")
                || simpleName.equals("Throwable");
    }

    /**
     * 계보 최상위(in-graph parent 가 없는) 노드의 simpleName 으로 통합 subsystem 이름을 만든다.
     * 예외 클래스는 단일 상속이라 보통 루트가 1개다.
     */
    private String lineageRootName(List<String> component,
                                   Map<String, String> parentOf,
                                   Map<String, ProjectedNode> nodeById) {
        List<String> roots = component.stream()
                .filter(member -> !parentOf.containsKey(member))
                .sorted()
                .toList();
        String rootSymbolId = roots.isEmpty() ? component.get(0) : roots.get(0);
        ProjectedNode rootNode = nodeById.get(rootSymbolId);
        if (rootNode == null || rootNode.getSimpleName() == null || rootNode.getSimpleName().isBlank()) {
            return "exception";
        }
        return rootNode.getSimpleName();
    }
}
