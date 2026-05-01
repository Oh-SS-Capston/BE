package com.example.ossdoc.domain.cluster.service;

import com.example.ossdoc.domain.cluster.model.ProjectedGraph;
import com.example.ossdoc.domain.cluster.model.ProjectedNode;
import com.example.ossdoc.domain.cluster.model.subsystem.Subsystem;
import com.example.ossdoc.domain.cluster.support.PackageTokenExtractor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
public class SubsystemAssembler {

    private final PackageTokenExtractor packageTokenExtractor;

    public List<Subsystem> assemble(ProjectedGraph graph, int[] clusters, int minClusterSize) {
        Map<Integer, List<ProjectedNode>> grouped = new HashMap<>();

        for (int i = 0; i < clusters.length; i++) {
            grouped.computeIfAbsent(clusters[i], k -> new ArrayList<>())
                    .add(graph.getNodes().get(i));
        }

        //너무 작은 cluster는 일단 버리고, 추후 흡수 로직을 넣을 수 있음
        List<Map.Entry<Integer, List<ProjectedNode>>> filtered = grouped.entrySet().stream()
                .filter(e -> e.getValue().size() >= minClusterSize)
                .toList();

        List<Subsystem> subsystems = new ArrayList<>();
        int seq = 1;

        for (Map.Entry<Integer, List<ProjectedNode>> entry : filtered) {
            List<ProjectedNode> members = entry.getValue();

            List<String> symbolIds = members.stream()
                    .map(ProjectedNode::getSymbolId)
                    .toList();

            List<String> entrySymbols = members.stream()
                    .filter(ProjectedNode::isPublicApi)
                    .map(ProjectedNode::getSymbolId)
                    .toList();

            List<String> packageRoots = members.stream()
                    .map(ProjectedNode::getPackageName)
                    .filter(pkg -> pkg != null && !pkg.isBlank())
                    .distinct()
                    .sorted()
                    .toList();

            String label = packageTokenExtractor.labelOf(members);

            subsystems.add(Subsystem.builder()
                    .subsystemId(String.format("ss_%03d", seq++))
                    .name(label)
                    .score(0.0) //RankingService에서 채움
                    .memberSymbolIds(symbolIds)
                    .entrySymbolIds(entrySymbols)
                    .coreSymbolIds(List.of())
                    .packageRoots(packageRoots)
                    .build());
        }

        return subsystems;
    }
}