package com.example.ossdoc.domain.cluster.support.supercluster;

import com.example.ossdoc.domain.cluster.config.ClusterSignalProperties;
import com.example.ossdoc.domain.cluster.model.ProjectedNode;
import com.example.ossdoc.domain.cluster.model.subsystem.Subsystem;
import com.example.ossdoc.domain.cluster.model.subsystem.SuperSubsystem;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ModuleIdMergeStrategyTest {

    private ModuleIdMergeStrategy strategy;
    private ClusterSignalProperties.SuperCluster defaultConfig;

    @BeforeEach
    void setUp() {
        strategy = new ModuleIdMergeStrategy();
        defaultConfig = new ClusterSignalProperties.SuperCluster();
        // minSuperSize=10, fallback.depth=4 (기본값)
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private ProjectedNode node(String symbolId, String moduleId, String packageName) {
        return ProjectedNode.builder()
                .symbolId(symbolId)
                .moduleId(moduleId)
                .packageName(packageName)
                .qualifiedName((packageName != null ? packageName + "." : "") + symbolId)
                .simpleName(symbolId)
                .build();
    }

    private Subsystem subsystem(String id, List<String> symbolIds, List<String> pkgRoots) {
        return Subsystem.builder()
                .subsystemId(id)
                .name(id)
                .score(0.0)
                .memberSymbolIds(symbolIds)
                .entrySymbolIds(List.of())
                .coreSymbolIds(List.of())
                .packageRoots(pkgRoots)
                .build();
    }

    private MergeContext ctx(
            List<Subsystem> subs,
            Map<String, ProjectedNode> nodeIndex,
            Map<String, String> displayNames) {
        return new MergeContext(subs, nodeIndex, displayNames, defaultConfig);
    }

    // ── 테스트 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("모든 멤버 동일 moduleId → 단일 super-cluster 생성")
    void allSameModuleId_singleSuperCluster() {
        Map<String, ProjectedNode> nodeIndex = new HashMap<>();
        List<Subsystem> subs = new ArrayList<>();

        for (int s = 1; s <= 3; s++) {
            List<String> symbolIds = new ArrayList<>();
            for (int i = 1; i <= 5; i++) {
                String sid = "sym_s" + s + "_" + i;
                nodeIndex.put(sid, node(sid, "junit-jupiter-api", "org.junit.jupiter.api"));
                symbolIds.add(sid);
            }
            subs.add(subsystem("ss_00" + s, symbolIds, List.of("org.junit.jupiter.api")));
        }

        List<SuperSubsystem> result = strategy.merge(
                ctx(subs, nodeIndex, Map.of("junit-jupiter-api", "junit-jupiter-api")));

        assertThat(result).hasSize(1);
        SuperSubsystem sup = result.get(0);
        assertThat(sup.getSuperSubsystemId()).isEqualTo("sup_001");
        assertThat(sup.getCanonicalKey()).isEqualTo("junit-jupiter-api");
        assertThat(sup.getDisplayName()).isEqualTo("junit-jupiter-api");
        assertThat(sup.getMemberSubsystemIds()).containsExactlyInAnyOrder("ss_001", "ss_002", "ss_003");
        assertThat(sup.getMemberCount()).isEqualTo(15);
        assertThat(sup.getModuleAffinity().get("junit-jupiter-api")).isCloseTo(1.0, Offset.offset(0.001));
    }

    @Test
    @DisplayName("mixed moduleId → dominant 귀속 + moduleAffinity 비율 보존")
    void mixedModuleId_dominantAndAffinityPreserved() {
        Map<String, ProjectedNode> nodeIndex = new HashMap<>();
        List<String> symbolIds = new ArrayList<>();

        // 6개 → junit-jupiter-api, 4개 → junit-jupiter-engine
        for (int i = 1; i <= 6; i++) {
            String sid = "api_sym_" + i;
            nodeIndex.put(sid, node(sid, "junit-jupiter-api", "org.junit.jupiter.api"));
            symbolIds.add(sid);
        }
        for (int i = 1; i <= 4; i++) {
            String sid = "eng_sym_" + i;
            nodeIndex.put(sid, node(sid, "junit-jupiter-engine", "org.junit.jupiter.engine"));
            symbolIds.add(sid);
        }

        Subsystem sub = subsystem("ss_001", symbolIds,
                List.of("org.junit.jupiter.api", "org.junit.jupiter.engine"));

        List<SuperSubsystem> result = strategy.merge(ctx(List.of(sub), nodeIndex, Map.of()));

        assertThat(result).hasSize(1);
        SuperSubsystem sup = result.get(0);
        assertThat(sup.getCanonicalKey()).isEqualTo("junit-jupiter-api");
        assertThat(sup.getModuleAffinity()).hasSize(2);
        assertThat(sup.getModuleAffinity().get("junit-jupiter-api")).isCloseTo(0.6, Offset.offset(0.001));
        assertThat(sup.getModuleAffinity().get("junit-jupiter-engine")).isCloseTo(0.4, Offset.offset(0.001));
    }

    @Test
    @DisplayName("moduleId null → packageRoot prefix fallback 동작 (depth=4)")
    void nullModuleId_packageRootFallback() {
        Map<String, ProjectedNode> nodeIndex = new HashMap<>();
        List<String> symbolIds = new ArrayList<>();

        for (int i = 1; i <= 10; i++) {
            String sid = "sym_" + i;
            // depth=4 → "org.junit.jupiter.api.extension" → "org.junit.jupiter.api"
            nodeIndex.put(sid, node(sid, null, "org.junit.jupiter.api.extension"));
            symbolIds.add(sid);
        }

        Subsystem sub = subsystem("ss_001", symbolIds, List.of("org.junit.jupiter.api.extension"));

        List<SuperSubsystem> result = strategy.merge(ctx(List.of(sub), nodeIndex, Map.of()));

        assertThat(result).hasSize(1);
        SuperSubsystem sup = result.get(0);
        assertThat(sup.getCanonicalKey()).isEqualTo("org.junit.jupiter.api");
        assertThat(sup.getModuleAffinity()).containsKey("org.junit.jupiter.api");
        assertThat(sup.getModuleAffinity().get("org.junit.jupiter.api")).isCloseTo(1.0, Offset.offset(0.001));
    }

    @Test
    @DisplayName("minSuperSize 미달 + 부모 없음 → super_misc으로 분리")
    void belowMinSuperSize_noParent_movedToSuperMisc() {
        Map<String, ProjectedNode> nodeIndex = new HashMap<>();
        List<String> symbolIds = new ArrayList<>();

        // 3개 심볼 (< minSuperSize=10), 부모 키 없음
        for (int i = 1; i <= 3; i++) {
            String sid = "sym_" + i;
            nodeIndex.put(sid, node(sid, "junit-platform-suite", "org.junit.platform.suite"));
            symbolIds.add(sid);
        }

        Subsystem sub = subsystem("ss_001", symbolIds, List.of("org.junit.platform.suite"));

        List<SuperSubsystem> result = strategy.merge(ctx(List.of(sub), nodeIndex, Map.of()));

        assertThat(result).hasSize(1);
        SuperSubsystem misc = result.get(0);
        assertThat(misc.getCanonicalKey()).isEqualTo("super_misc");
        assertThat(misc.getMemberSubsystemIds()).containsExactly("ss_001");
        assertThat(misc.getMemberCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("minSuperSize 미달이지만 부모 키 존재 → 부모 super-cluster로 흡수")
    void belowMinSuperSize_parentExists_absorbedIntoParent() {
        Map<String, ProjectedNode> nodeIndex = new HashMap<>();

        // Large: "junit-jupiter" — 15개 심볼 (≥ minSuperSize=10)
        List<String> largeSymbols = new ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            String sid = "large_sym_" + i;
            nodeIndex.put(sid, node(sid, "junit-jupiter", "org.junit.jupiter"));
            largeSymbols.add(sid);
        }

        // Small: "junit-jupiter-params" — 3개 심볼 (< minSuperSize=10)
        // "junit-jupiter" 는 "junit-jupiter-params" 의 `-` 구분 부모 → 흡수
        List<String> smallSymbols = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            String sid = "small_sym_" + i;
            nodeIndex.put(sid, node(sid, "junit-jupiter-params", "org.junit.jupiter.params"));
            smallSymbols.add(sid);
        }

        List<Subsystem> subs = List.of(
                subsystem("ss_001", largeSymbols, List.of("org.junit.jupiter")),
                subsystem("ss_002", smallSymbols, List.of("org.junit.jupiter.params")));

        List<SuperSubsystem> result = strategy.merge(ctx(subs, nodeIndex, Map.of()));

        assertThat(result).hasSize(1);
        SuperSubsystem sup = result.get(0);
        assertThat(sup.getCanonicalKey()).isEqualTo("junit-jupiter");
        assertThat(sup.getMemberSubsystemIds()).containsExactlyInAnyOrder("ss_001", "ss_002");
        assertThat(sup.getMemberCount()).isEqualTo(18);
        // 15/18 ≈ 0.833, 3/18 ≈ 0.167
        assertThat(sup.getModuleAffinity().get("junit-jupiter")).isCloseTo(15.0 / 18, Offset.offset(0.001));
        assertThat(sup.getModuleAffinity().get("junit-jupiter-params")).isCloseTo(3.0 / 18, Offset.offset(0.001));
    }

    @Test
    @DisplayName("동수 투표 → 사전식 최솟값으로 canonical key 결정")
    void tiedVote_lexicographicMinWins() {
        Map<String, ProjectedNode> nodeIndex = new HashMap<>();
        List<String> symbolIds = new ArrayList<>();

        // 5개 → "junit-jupiter-api", 5개 → "junit-jupiter-engine" (동수)
        // 사전식: "junit-jupiter-api" < "junit-jupiter-engine" → api 승
        for (int i = 1; i <= 5; i++) {
            String sid = "api_" + i;
            nodeIndex.put(sid, node(sid, "junit-jupiter-api", "org.junit.jupiter.api"));
            symbolIds.add(sid);
        }
        for (int i = 1; i <= 5; i++) {
            String sid = "eng_" + i;
            nodeIndex.put(sid, node(sid, "junit-jupiter-engine", "org.junit.jupiter.engine"));
            symbolIds.add(sid);
        }

        Subsystem sub = subsystem("ss_001", symbolIds, List.of());

        List<SuperSubsystem> result = strategy.merge(ctx(List.of(sub), nodeIndex, Map.of()));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCanonicalKey()).isEqualTo("junit-jupiter-api");
    }

    @Test
    @DisplayName("displayNames에 canonicalKey 매핑 존재 → displayName으로 사용")
    void displayNameMapping_usedAsDisplayName() {
        Map<String, ProjectedNode> nodeIndex = new HashMap<>();
        List<String> symbolIds = new ArrayList<>();

        for (int i = 1; i <= 10; i++) {
            String sid = "sym_" + i;
            nodeIndex.put(sid, node(sid, "resilience4j-core", "io.github.resilience4j.core"));
            symbolIds.add(sid);
        }

        Subsystem sub = subsystem("ss_001", symbolIds, List.of("io.github.resilience4j.core"));
        List<SuperSubsystem> result = strategy.merge(
                ctx(List.of(sub), nodeIndex, Map.of("resilience4j-core", "resilience4j-core")));

        assertThat(result.get(0).getCanonicalKey()).isEqualTo("resilience4j-core");
        assertThat(result.get(0).getDisplayName()).isEqualTo("resilience4j-core");
    }

    @Test
    @DisplayName("displayNames에 매핑 없으면 canonicalKey를 displayName으로 fallback")
    void displayNameMissing_fallsBackToCanonicalKey() {
        Map<String, ProjectedNode> nodeIndex = new HashMap<>();
        List<String> symbolIds = new ArrayList<>();

        for (int i = 1; i <= 10; i++) {
            String sid = "sym_" + i;
            nodeIndex.put(sid, node(sid, null, "org.junit.jupiter.api.extension"));
            symbolIds.add(sid);
        }

        Subsystem sub = subsystem("ss_001", symbolIds, List.of("org.junit.jupiter.api.extension"));
        List<SuperSubsystem> result = strategy.merge(ctx(List.of(sub), nodeIndex, Map.of()));

        assertThat(result.get(0).getDisplayName()).isEqualTo("org.junit.jupiter.api");
    }

    @Test
    @DisplayName("level-1 subsystem 없으면 빈 목록 반환")
    void emptyInput_returnsEmpty() {
        List<SuperSubsystem> result = strategy.merge(
                ctx(List.of(), Map.of(), Map.of()));

        assertThat(result).isEmpty();
    }
}
