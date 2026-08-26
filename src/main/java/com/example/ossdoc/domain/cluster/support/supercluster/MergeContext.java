package com.example.ossdoc.domain.cluster.support.supercluster;

import com.example.ossdoc.domain.cluster.config.ClusterSignalProperties;
import com.example.ossdoc.domain.cluster.model.ProjectedNode;
import com.example.ossdoc.domain.cluster.model.subsystem.Subsystem;

import java.util.List;
import java.util.Map;

/**
 * level-2 super-cluster 병합 전략에 전달되는 입력 컨텍스트.
 *
 * @param level1Subsystems level-1 subsystem 목록 (SUBSYSTEMS_JSON에서 복원)
 * @param nodeIndex        symbolId → ProjectedNode (moduleId 조회용)
 * @param displayNames     canonicalKey(moduleId 또는 packageRoot) → 표시 이름.
 *                         값이 없으면 canonicalKey 자체를 displayName으로 사용한다.
 *                         (Option 3: BUILD_MANIFEST 기반 메모리 매핑. DB 무변경)
 * @param config           super-cluster 설정 (minSuperSize, strategy 등)
 */
public record MergeContext(
        List<Subsystem> level1Subsystems,
        Map<String, ProjectedNode> nodeIndex,
        Map<String, String> displayNames,
        ClusterSignalProperties.SuperCluster config
) {}
