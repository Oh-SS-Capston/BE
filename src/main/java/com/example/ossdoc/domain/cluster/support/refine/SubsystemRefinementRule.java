package com.example.ossdoc.domain.cluster.support.refine;

import com.example.ossdoc.domain.cluster.model.ProjectedNode;
import com.example.ossdoc.domain.cluster.model.subsystem.Subsystem;

import java.util.List;

/**
 * Leiden 결과를 결정적 구조 규칙으로 후처리하는 단일 보정 규칙.
 * refactplan.md 1순위 SubsystemRefiner 의 구성 요소.
 *
 * <p>결정적/이산적 규칙만 Rule 로 둔다. 통계적/연속적 신호는 Signal(가상 엣지) 책임이다.
 */
public interface SubsystemRefinementRule {

    /** 규칙 식별자. 산출물 메타 키로도 쓰인다. ("OWNER", "EXCEPTION") */
    String name();

    /** 설정 토글. */
    boolean enabled();

    /**
     * 보정을 적용해 새 subsystem 목록을 반환한다.
     *
     * @param subsystems 직전 단계까지 조립된 subsystem 목록
     * @param nodes      투영 그래프의 전체 노드
     * @param runId      raw edge 직접 조회가 필요한 규칙(EXTENDS 계보 등)을 위한 run 식별자
     */
    RefinementResult apply(List<Subsystem> subsystems, List<ProjectedNode> nodes, String runId);
}
