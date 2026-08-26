package com.example.ossdoc.domain.cluster.support.refine;

import com.example.ossdoc.domain.cluster.config.ClusterSignalProperties;
import com.example.ossdoc.domain.cluster.model.ProjectedNode;
import com.example.ossdoc.domain.cluster.model.subsystem.Subsystem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * owner(내부 클래스·Builder·중첩 enum 등) 노드를 outer 가 속한 subsystem 으로 흡수한다.
 *
 * <p>owner 관계는 1:1 결정적 관계라 그래프 가중치가 아닌 후처리 규칙으로 처리한다.
 * Leiden 이 inner 와 outer 를 다른 클러스터로 흩어 놓은 경우를 바로잡는다.
 *
 * <p>흡수 효과는 4개 메타로 분리 집계해 정직하게 평가한다.
 * <ul>
 *   <li>absorbed_count: 규칙이 owner 결속으로 처리한 노드 총수</li>
 *   <li>effective_from_misc_to_named: misc → 명명 subsystem 으로 실제 이동 (misc 비율 감소 기여)</li>
 *   <li>kept_inside_misc: owner 와 함께 misc 에 남음 (구조 정합성만 향상)</li>
 *   <li>from_other_to_owner_subsystem: 다른 subsystem → owner subsystem 재배치</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OwnerAbsorptionRule implements SubsystemRefinementRule {

    /** SubsystemAssembler 가 작은 클러스터를 모아 부여하는 라벨. */
    private static final String MISC_LABEL = "misc";

    private final ClusterSignalProperties properties;
    private final SubsystemReassembler reassembler;

    @Override
    public String name() {
        return "OWNER";
    }

    @Override
    public boolean enabled() {
        return properties.getRefiner().getRules().getOwner().isEnabled();
    }

    @Override
    public RefinementResult apply(List<Subsystem> subsystems, List<ProjectedNode> nodes, String runId) {
        int maxDepth = properties.getRefiner().getRules().getOwner().getMaxDepth();

        // ownerSymbol 은 owner 의 qualifiedName 으로 투영된다 → qualifiedName 으로 owner 노드를 찾는다.
        Map<String, ProjectedNode> nodeById = new HashMap<>();
        Map<String, ProjectedNode> nodeByQName = new HashMap<>();
        for (ProjectedNode node : nodes) {
            if (node.getSymbolId() != null) nodeById.put(node.getSymbolId(), node);
            if (node.getQualifiedName() != null) nodeByQName.put(node.getQualifiedName(), node);
        }

        List<String> orderedIds = subsystems.stream().map(Subsystem::getSubsystemId).toList();
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
        // owner 의 subsystem 은 다른 노드 재배치에 영향받지 않도록 원본 스냅샷에서 해석한다.
        Map<String, String> originalPlacement = Map.copyOf(placement);

        int absorbed = 0;
        int fromMiscToNamed = 0;
        int keptInsideMisc = 0;
        int fromOtherToOwner = 0;

        // symbolId 정렬 순회로 재현성을 보장한다.
        List<ProjectedNode> ordered = nodes.stream()
                .filter(node -> node.getSymbolId() != null)
                .sorted(Comparator.comparing(ProjectedNode::getSymbolId))
                .toList();

        for (ProjectedNode node : ordered) {
            if (node.getOwnerSymbol() == null) {
                continue;
            }

            String rootOwnerId = resolveRootOwner(node, nodeByQName, maxDepth);
            if (rootOwnerId == null || rootOwnerId.equals(node.getSymbolId())) {
                continue;
            }

            String ownerSubsystem = originalPlacement.get(rootOwnerId);
            String nodeSubsystem = placement.get(node.getSymbolId());
            if (ownerSubsystem == null || nodeSubsystem == null) {
                continue;
            }

            boolean nodeInMisc = miscSubsystemIds.contains(nodeSubsystem);
            boolean ownerInMisc = miscSubsystemIds.contains(ownerSubsystem);

            if (nodeSubsystem.equals(ownerSubsystem)) {
                // 이미 owner 와 같은 subsystem. misc 내부면 진단용으로만 집계한다.
                // (이 수치가 높으면 후속 OwnerGroupPromotionRule 도입 신호가 된다.)
                if (nodeInMisc) {
                    keptInsideMisc++;
                    absorbed++;
                }
                continue;
            }

            placement.put(node.getSymbolId(), ownerSubsystem);
            absorbed++;
            if (nodeInMisc && !ownerInMisc) {
                fromMiscToNamed++;
            } else {
                fromOtherToOwner++;
            }
        }

        List<Subsystem> refined = reassembler.reassemble(orderedIds, names, placement, nodeById);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("absorbed_count", absorbed);
        meta.put("effective_from_misc_to_named", fromMiscToNamed);
        meta.put("kept_inside_misc", keptInsideMisc);
        meta.put("from_other_to_owner_subsystem", fromOtherToOwner);

        log.info("[CLUSTER-REFINER] OWNER rule. absorbed={}, misc->named={}, keptInMisc={}, other->owner={}",
                absorbed, fromMiscToNamed, keptInsideMisc, fromOtherToOwner);

        return new RefinementResult(refined, meta);
    }

    /**
     * owner 체인을 maxDepth 까지 거슬러 올라가 최상위 owner 의 symbolId 를 반환한다.
     * owner 를 한 번도 해석하지 못하면 null 을 반환한다. 깊이 cap 으로 순환을 방지한다.
     */
    private String resolveRootOwner(ProjectedNode node, Map<String, ProjectedNode> nodeByQName, int maxDepth) {
        ProjectedNode current = node;
        ProjectedNode resolved = null;
        for (int depth = 0; depth < maxDepth; depth++) {
            String ownerQName = current.getOwnerSymbol();
            if (ownerQName == null) {
                break;
            }
            ProjectedNode ownerNode = nodeByQName.get(ownerQName);
            if (ownerNode == null || ownerNode == current) {
                break; // owner 가 그래프 밖이거나 자기 자신이면 중단한다.
            }
            current = ownerNode;
            resolved = ownerNode;
        }
        return resolved == null ? null : resolved.getSymbolId();
    }
}
