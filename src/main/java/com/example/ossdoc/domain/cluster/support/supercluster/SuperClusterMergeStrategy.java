package com.example.ossdoc.domain.cluster.support.supercluster;

import com.example.ossdoc.domain.cluster.model.subsystem.SuperSubsystem;

import java.util.List;

/**
 * level-1 subsystem → level-2 super-cluster 병합 전략 인터페이스.
 *
 * 구현체:
 * - {@link ModuleIdMergeStrategy}: moduleId 다수결(결정적, Phase 1)
 * - MetaGraphLeidenStrategy: meta-graph Leiden (통계적, Phase 2 — 조건부)
 */
public interface SuperClusterMergeStrategy {

    /** 산출물 strategy.name 필드에 기록되는 식별자. */
    String strategyName();

    /**
     * level-1 subsystem 목록을 super-cluster 목록으로 변환한다.
     *
     * @param ctx 병합에 필요한 입력 일체 (subsystem, nodeIndex, moduleIndex, config)
     * @return super-cluster 목록 (sup_NNN ID 할당 완료)
     */
    List<SuperSubsystem> merge(MergeContext ctx);
}
