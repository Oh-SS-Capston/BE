package com.example.ossdoc.domain.cluster.service;

import com.example.ossdoc.domain.cluster.config.ClusterSignalProperties;
import com.example.ossdoc.domain.cluster.model.ProjectedNode;
import com.example.ossdoc.domain.cluster.model.subsystem.Subsystem;
import com.example.ossdoc.domain.cluster.support.refine.ExceptionLineageRule;
import com.example.ossdoc.domain.cluster.support.refine.OwnerAbsorptionRule;
import com.example.ossdoc.domain.cluster.support.refine.RefinementResult;
import com.example.ossdoc.domain.cluster.support.refine.SubsystemRefinementRule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Leiden 결과를 결정적 구조 규칙으로 후처리한다. (refactplan.md 1순위)
 *
 * <p>규칙은 owner 흡수 → exception 계보 통합 순으로 적용한다(계보 우선 원칙).
 * 한 규칙의 실패가 군집화 산출물 생성 자체를 막지 않도록 규칙 단위로 예외를 격리한다.
 *
 * <p>각 단계 효과를 객관적으로 비교할 수 있도록 baseline 메타(misc_before/after, 규칙별 수치)를
 * 모아 산출물 algorithm.refiner 에 기록한다.
 */
@Slf4j
@Service
public class SubsystemRefinerService {

    private static final String MISC_LABEL = "misc";

    private final ClusterSignalProperties properties;
    private final List<SubsystemRefinementRule> rules;

    public SubsystemRefinerService(ClusterSignalProperties properties,
                                   OwnerAbsorptionRule ownerAbsorptionRule,
                                   ExceptionLineageRule exceptionLineageRule) {
        this.properties = properties;
        // 적용 순서 고정: owner 흡수 후 exception 계보 통합.
        this.rules = List.of(ownerAbsorptionRule, exceptionLineageRule);
    }

    /**
     * 보정 결과 subsystem 목록과 산출물 기록용 메타.
     */
    public record RefineOutcome(List<Subsystem> subsystems, Map<String, Object> refinerMeta) {
    }

    public RefineOutcome refine(List<Subsystem> subsystems, List<ProjectedNode> nodes, String runId) {
        Map<String, Object> refinerMeta = new LinkedHashMap<>();

        if (!properties.getRefiner().isEnabled()) {
            refinerMeta.put("enabled", false);
            log.info("[CLUSTER-REFINER] disabled. subsystems passthrough.");
            return new RefineOutcome(subsystems, refinerMeta);
        }
        refinerMeta.put("enabled", true);

        int miscBefore = countMiscNodes(subsystems);

        List<Subsystem> current = subsystems;
        Map<String, Object> rulesMeta = new LinkedHashMap<>();

        for (SubsystemRefinementRule rule : rules) {
            String key = rule.name().toLowerCase();
            if (!rule.enabled()) {
                rulesMeta.put(key, Map.of("enabled", false));
                continue;
            }
            try {
                RefinementResult result = rule.apply(current, nodes, runId);
                current = result.subsystems();
                Map<String, Object> ruleMeta = new LinkedHashMap<>();
                ruleMeta.put("enabled", true);
                ruleMeta.putAll(result.meta());
                rulesMeta.put(key, ruleMeta);
            } catch (Exception e) {
                // 보정 규칙 1건의 실패가 군집화 산출물 생성을 막지 않도록 격리한다.
                log.error("[CLUSTER-REFINER] rule {} failed. skipping this rule.", rule.name(), e);
                rulesMeta.put(key, Map.of("enabled", true, "error", e.getClass().getSimpleName()));
            }
        }

        int miscAfter = countMiscNodes(current);

        refinerMeta.put("rules", rulesMeta);
        refinerMeta.put("misc_before", miscBefore);
        refinerMeta.put("misc_after", miscAfter);

        log.info("[CLUSTER-REFINER] done. miscBefore={}, miscAfter={}, subsystemCount={}",
                miscBefore, miscAfter, current.size());

        return new RefineOutcome(current, refinerMeta);
    }

    /**
     * misc subsystem 에 남은 노드 수를 집계한다. 보정 효과의 핵심 지표다.
     */
    private int countMiscNodes(List<Subsystem> subsystems) {
        return subsystems.stream()
                .filter(subsystem -> MISC_LABEL.equals(subsystem.getName()))
                .mapToInt(subsystem -> subsystem.getMemberSymbolIds() == null
                        ? 0
                        : subsystem.getMemberSymbolIds().size())
                .sum();
    }
}
