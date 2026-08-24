package com.example.ossdoc.domain.cluster.support;

import com.example.ossdoc.domain.cluster.model.ProjectedEdge;
import com.example.ossdoc.domain.cluster.model.ProjectedGraph;
import com.example.ossdoc.domain.cluster.model.ProjectedNode;
import com.example.ossdoc.domain.cluster.model.subsystem.Subsystem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Leiden의 community 결과를 최종 Subsystem 객체로 조립한다.
 *
 * 기존 구현에서는 minClusterSize보다 작은 모든 cluster를
 * 하나의 misc subsystem에 합쳤다.
 *
 * 개선 버전에서는 작은 cluster가 정상 cluster와 연결되어 있다면
 * 가장 강하게 연결된 정상 cluster로 우선 흡수한다.
 *
 * 정상 cluster와 전혀 연결되지 않은 작은 cluster만 misc로 이동한다.
 */
@Component
@RequiredArgsConstructor
public class SubsystemAssembler {

    private static final String SMALL_CLUSTER_LABEL = "misc";

    private final PackageTokenExtractor packageTokenExtractor;

    /**
     * Leiden cluster assignment를 최종 Subsystem 목록으로 변환한다.
     */
    public List<Subsystem> assemble(
            ProjectedGraph graph,
            int[] clusters,
            int minClusterSize
    ) {
        /*
         * projected node 개수와 clustering assignment 개수가 다르면
         * index 기반 mapping 자체가 잘못된 상태이므로 즉시 실패시킨다.
         */
        if (clusters == null || clusters.length != graph.getNodes().size()) {
            throw new IllegalArgumentException(
                    "cluster assignment size must match projected nodes"
            );
        }

        /*
         * clusterId -> 해당 cluster에 속한 projected node index 목록.
         *
         * TreeMap을 사용하여 cluster 순서를 일정하게 유지한다.
         */
        Map<Integer, List<Integer>> membersByCluster = new TreeMap<>();

        for (int i = 0; i < clusters.length; i++) {
            membersByCluster
                    .computeIfAbsent(clusters[i], ignored -> new ArrayList<>())
                    .add(i);
        }

        Set<Integer> largeClusters = new TreeSet<>();
        Set<Integer> smallClusters = new TreeSet<>();

        // minClusterSize를 기준으로 정상 cluster와 작은 cluster를 분리한다.
        for (Map.Entry<Integer, List<Integer>> entry : membersByCluster.entrySet()) {
            if (entry.getValue().size() >= minClusterSize) {
                largeClusters.add(entry.getKey());
            } else {
                smallClusters.add(entry.getKey());
            }
        }

        /*
         * 최종 정상 subsystem이 될 group.
         *
         * 우선 large cluster의 원래 member를 그대로 넣는다.
         */
        Map<Integer, List<Integer>> finalGroups = new TreeMap<>();

        for (Integer clusterId : largeClusters) {
            finalGroups.put(
                    clusterId,
                    new ArrayList<>(membersByCluster.get(clusterId))
            );
        }

        // 어디에도 흡수되지 못한 작은 cluster의 node만 최종 misc로 이동한다.
        List<Integer> miscIndexes = new ArrayList<>();

        for (Integer smallCluster : smallClusters) {
            /*
             * 해당 작은 cluster가 어떤 정상 cluster와 가장 강하게 연결되는지 계산한다.
             */
            Integer target = strongestLargeNeighbor(
                    smallCluster,
                    clusters,
                    largeClusters,
                    graph.getEdges()
            );

            if (target == null) {
                /*
                 * 정상 cluster와 연결이 없다면
                 * 기존처럼 misc 후보로 보낸다.
                 */
                miscIndexes.addAll(
                        membersByCluster.get(smallCluster)
                );
            } else {
                /*
                 * 정상 cluster와 연결되어 있다면
                 * 가장 강한 cluster로 작은 cluster 전체를 흡수한다.
                 */
                finalGroups
                        .get(target)
                        .addAll(
                                membersByCluster.get(smallCluster)
                        );
            }
        }

        List<Subsystem> subsystems = new ArrayList<>();

        /*
         * 현재 단계의 ss_001 형식 ID는 임시 ID다.
         *
         * Refiner까지 모두 끝난 뒤 SubsystemIdentityService에서
         * 최종 deterministic ID로 다시 변경한다.
         */
        int seq = 1;

        for (List<Integer> indexes : finalGroups.values()) {
            List<ProjectedNode> members = indexes
                    .stream()
                    .sorted()
                    .map(graph.getNodes()::get)
                    .toList();

            subsystems.add(
                    buildSubsystem(
                            String.format("ss_%03d", seq++),
                            null,
                            members
                    )
            );
        }

        // 정상 cluster와 연결되지 않은 작은 cluster가 있을 때만 misc를 생성한다.
        if (!miscIndexes.isEmpty()) {
            List<ProjectedNode> miscMembers = miscIndexes
                    .stream()
                    .sorted()
                    .map(graph.getNodes()::get)
                    .toList();

            subsystems.add(
                    buildSubsystem(
                            String.format("ss_%03d", seq),
                            SMALL_CLUSTER_LABEL,
                            miscMembers
                    )
            );
        }

        return subsystems;
    }

    /**
     * small cluster와 가장 강하게 연결된 large cluster를 찾는다.
     *
     * small cluster와 각 large cluster 사이의 모든 edge weight를 합산한 뒤
     * 가장 큰 합계를 가진 cluster를 선택한다.
     */
    private Integer strongestLargeNeighbor(
            int smallCluster,
            int[] clusters,
            Set<Integer> largeClusters,
            List<ProjectedEdge> edges
    ) {
        Map<Integer, Double> weightByTarget = new HashMap<>();

        for (ProjectedEdge edge : edges) {
            int fromCluster = clusters[edge.getFromIndex()];
            int toCluster = clusters[edge.getToIndex()];

            // 같은 cluster 내부 edge는 cluster 간 흡수 판단에 필요하지 않다.
            if (fromCluster == toCluster) {
                continue;
            }

            // small cluster -> large cluster 관계.
            if (fromCluster == smallCluster && largeClusters.contains(toCluster)) {
                weightByTarget.merge(
                        toCluster,
                        edge.getWeight(),
                        Double::sum
                );
            }

            // large cluster -> small cluster 관계.
            else if (toCluster == smallCluster && largeClusters.contains(fromCluster)) {
                weightByTarget.merge(
                        fromCluster,
                        edge.getWeight(),
                        Double::sum
                );
            }
        }

        /*
         * 총 연결 weight가 가장 큰 large cluster를 선택한다.
         *
         * 동일 weight인 경우에는 작은 cluster ID를 선택하여
         * 반복 실행 결과가 달라지지 않도록 한다.
         */
        return weightByTarget
                .entrySet()
                .stream()
                .max(
                        Comparator
                                .<Map.Entry<Integer, Double>>comparingDouble(Map.Entry::getValue)
                                .thenComparing(entry -> -entry.getKey())
                )
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /**
     * ProjectedNode 목록을 실제 Subsystem 객체로 변환한다.
     */
    private Subsystem buildSubsystem(
            String subsystemId,
            String fixedLabel,
            List<ProjectedNode> members
    ) {
        /*
         * member ID 순서를 정렬한다.
         *
         * artifact 비교와 deterministic ID 계산을 안정적으로 만들기 위한 처리다.
         */
        List<String> symbolIds = members
                .stream()
                .map(ProjectedNode::getSymbolId)
                .filter(Objects::nonNull)
                .filter(id -> !id.isBlank())
                .sorted()
                .toList();

        // subsystem 내부 entry point symbol 목록.
        List<String> entrySymbols = members
                .stream()
                .filter(ProjectedNode::isEntryPoint)
                .map(ProjectedNode::getSymbolId)
                .filter(Objects::nonNull)
                .filter(id -> !id.isBlank())
                .sorted()
                .toList();

        // subsystem이 포함하는 package 목록.
        List<String> packageRoots = members
                .stream()
                .map(ProjectedNode::getPackageName)
                .filter(Objects::nonNull)
                .filter(pkg -> !pkg.isBlank())
                .distinct()
                .sorted()
                .toList();

        /*
         * misc는 이름을 강제로 misc로 사용한다.
         *
         * 일반 subsystem은 기존 PackageTokenExtractor를 이용하여
         * member package 정보를 기반으로 이름을 만든다.
         */
        String label = fixedLabel == null
                ? packageTokenExtractor.labelOf(members)
                : fixedLabel;

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