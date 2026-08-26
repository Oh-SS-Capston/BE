package com.example.ossdoc.domain.cluster.support.refine;

import com.example.ossdoc.domain.cluster.model.ProjectedNode;
import com.example.ossdoc.domain.cluster.model.subsystem.Subsystem;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * symbolId→subsystemId 배치 맵으로부터 subsystem 목록을 재구성한다.
 *
 * <p>보정 규칙이 노드를 재배치한 뒤, 변경된 멤버 구성에 맞춰 산출물을 정합하게 맞춘다.
 * <ul>
 *   <li>entry/packageRoots 를 새 멤버 구성으로 다시 계산한다.</li>
 *   <li>멤버가 0이 된 subsystem 은 제거한다.</li>
 *   <li>멤버 순서를 symbolId 정렬로 고정해 재현성을 보장한다.</li>
 * </ul>
 */
@Component
public class SubsystemReassembler {

    /**
     * @param orderedSubsystemIds 출력 subsystem 순서 (신규 생성된 ID 는 호출자가 append 해 둔다)
     * @param subsystemNames      subsystemId → 표시 이름
     * @param placement           symbolId → subsystemId 최종 배치
     * @param nodeById            symbolId → ProjectedNode (entry/package 재계산용)
     */
    public List<Subsystem> reassemble(List<String> orderedSubsystemIds,
                                      Map<String, String> subsystemNames,
                                      Map<String, String> placement,
                                      Map<String, ProjectedNode> nodeById) {

        Map<String, List<String>> grouped = new HashMap<>();
        for (Map.Entry<String, String> entry : placement.entrySet()) {
            grouped.computeIfAbsent(entry.getValue(), k -> new ArrayList<>()).add(entry.getKey());
        }

        List<Subsystem> result = new ArrayList<>();
        for (String subsystemId : orderedSubsystemIds) {
            List<String> members = grouped.get(subsystemId);
            if (members == null || members.isEmpty()) {
                continue; // 멤버가 모두 빠져나간 subsystem 은 제거한다.
            }

            List<String> sortedMembers = members.stream().sorted().toList();

            List<String> entrySymbols = sortedMembers.stream()
                    .filter(id -> {
                        ProjectedNode node = nodeById.get(id);
                        return node != null && node.isEntryPoint();
                    })
                    .toList();

            List<String> packageRoots = sortedMembers.stream()
                    .map(nodeById::get)
                    .filter(Objects::nonNull)
                    .map(ProjectedNode::getPackageName)
                    .filter(pkg -> pkg != null && !pkg.isBlank())
                    .distinct()
                    .sorted()
                    .toList();

            result.add(Subsystem.builder()
                    .subsystemId(subsystemId)
                    .name(subsystemNames.get(subsystemId))
                    .score(0.0)
                    .memberSymbolIds(sortedMembers)
                    .entrySymbolIds(entrySymbols)
                    .coreSymbolIds(List.of())
                    .packageRoots(packageRoots)
                    .build());
        }
        return result;
    }
}
