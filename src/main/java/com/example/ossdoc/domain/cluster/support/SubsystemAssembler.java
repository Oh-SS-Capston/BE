package com.example.ossdoc.domain.cluster.support;

import com.example.ossdoc.domain.cluster.model.ProjectedGraph;
import com.example.ossdoc.domain.cluster.model.ProjectedNode;
import com.example.ossdoc.domain.cluster.model.subsystem.Subsystem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class SubsystemAssembler {
    private static final String SMALL_CLUSTER_LABEL = "misc";

    private final PackageTokenExtractor packageTokenExtractor;

    /**
     * Leiden 결과 클러스터를 사용자 노출용 subsystem으로 조립한다.
     * - minClusterSize 미만의 작은 클러스터는 버리지 않고 misc subsystem으로 흡수해 정보 손실을 줄인다.
     */
    public List<Subsystem> assemble(ProjectedGraph graph, int[] clusters, int minClusterSize) {
        Map<Integer, List<ProjectedNode>> grouped = new HashMap<>();

        for (int i = 0; i < clusters.length; i++) {
            grouped.computeIfAbsent(clusters[i], k -> new ArrayList<>())
                    .add(graph.getNodes().get(i));
        }

        // 클러스터 순서를 고정해 동일 입력에서 subsystem ID가 흔들리지 않도록 정렬한다.
        List<Map.Entry<Integer, List<ProjectedNode>>> orderedGroups = grouped.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();

        List<Subsystem> subsystems = new ArrayList<>();
        List<ProjectedNode> smallClusterMembers = new ArrayList<>();
        int seq = 1;

        for (Map.Entry<Integer, List<ProjectedNode>> entry : orderedGroups) {
            List<ProjectedNode> members = entry.getValue();
            if (members.size() >= minClusterSize) {
                subsystems.add(buildSubsystem(String.format("ss_%03d", seq++), null, members));
                continue;
            }
            smallClusterMembers.addAll(members);
        }

        // 작은 클러스터를 misc로 모아 노드가 subsystem 밖으로 누락되지 않도록 보완한다.
        if (!smallClusterMembers.isEmpty()) {
            subsystems.add(buildSubsystem(String.format("ss_%03d", seq), SMALL_CLUSTER_LABEL, smallClusterMembers));
        }

        return subsystems;
    }

    /**
     * 멤버 노드 목록으로 단일 subsystem 객체를 생성한다.
     */
    private Subsystem buildSubsystem(String subsystemId, String fixedLabel, List<ProjectedNode> members) {
        List<String> symbolIds = members.stream()
                .map(ProjectedNode::getSymbolId)
                .filter(symbolId -> symbolId != null && !symbolId.isBlank())
                .toList();

        List<String> entrySymbols = members.stream()
                .filter(ProjectedNode::isEntryPoint)
                .map(ProjectedNode::getSymbolId)
                .filter(symbolId -> symbolId != null && !symbolId.isBlank())
                .toList();

        List<String> packageRoots = members.stream()
                .map(ProjectedNode::getPackageName)
                .filter(pkg -> pkg != null && !pkg.isBlank())
                .distinct()
                .sorted()
                .toList();

        String label = fixedLabel == null ? packageTokenExtractor.labelOf(members) : fixedLabel;
        if (label == null || label.isBlank()) {
            label = "subsystem";
        }

        return Subsystem.builder()
                .subsystemId(subsystemId)
                .name(label)
                .score(0.0)
                .memberSymbolIds(symbolIds)
                .entrySymbolIds(entrySymbols)
                .coreSymbolIds(List.of())
                .packageRoots(packageRoots)
                .build();
    }
}