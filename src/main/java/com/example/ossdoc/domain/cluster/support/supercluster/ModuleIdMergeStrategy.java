package com.example.ossdoc.domain.cluster.support.supercluster;

import com.example.ossdoc.domain.cluster.model.ProjectedNode;
import com.example.ossdoc.domain.cluster.model.subsystem.Subsystem;
import com.example.ossdoc.domain.cluster.model.subsystem.SuperSubsystem;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * moduleId 다수결 기반 결정적 super-cluster 병합 전략 (Phase 1).
 *
 * <p>알고리즘 순서:
 * <ol>
 *   <li>각 level-1 subsystem의 member symbol에서 moduleId(우선) 또는 packageRoot prefix(fallback)로 투표</li>
 *   <li>다수결로 canonical key 결정; 동수 시 사전식 최솟값</li>
 *   <li>canonical key 기준으로 subsystem 그룹화 → 초기 super-cluster 생성</li>
 *   <li>memberCount < minSuperSize 그룹: 부모 키로 흡수 시도 → 불가 시 super_misc</li>
 *   <li>sup_NNN ID 할당 (super_misc 마지막)</li>
 * </ol>
 */
@Component
public class ModuleIdMergeStrategy implements SuperClusterMergeStrategy {

    private static final String SUPER_MISC_KEY = "super_misc";

    @Override
    public String strategyName() {
        return "MODULE_ID_MERGE";
    }

    @Override
    public List<SuperSubsystem> merge(MergeContext ctx) {
        if (ctx.level1Subsystems().isEmpty()) {
            return List.of();
        }

        // Step 1+2: canonical key 결정 후 그룹화
        Map<String, List<Subsystem>> grouped = new LinkedHashMap<>();
        for (Subsystem sub : ctx.level1Subsystems()) {
            String key = resolveCanonicalKey(sub, ctx);
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(sub);
        }

        // Step 3: 그룹별 SuperSubsystem 생성
        List<SuperSubsystem> initial = grouped.entrySet().stream()
                .map(e -> buildSuperSubsystem(e.getKey(), e.getValue(), ctx))
                .collect(Collectors.toList());

        // Step 4: 소형 그룹 흡수 또는 super_misc
        int minSize = ctx.config().getMinSuperSize();
        List<SuperSubsystem> large = initial.stream()
                .filter(s -> s.getMemberCount() >= minSize)
                .collect(Collectors.toList());
        List<SuperSubsystem> small = initial.stream()
                .filter(s -> s.getMemberCount() < minSize)
                .collect(Collectors.toList());

        List<SuperSubsystem> merged = absorb(large, small, ctx);

        // Step 5: sup_NNN ID 할당
        return assignIds(merged);
    }

    // ── canonical key 결정 ────────────────────────────────────────────────────

    private String resolveCanonicalKey(Subsystem sub, MergeContext ctx) {
        int depth = ctx.config().getFallback().getPackageRootCommonPrefixDepth();
        Map<String, Integer> votes = new HashMap<>();

        for (String symbolId : sub.getMemberSymbolIds()) {
            ProjectedNode node = ctx.nodeIndex().get(symbolId);
            if (node == null) continue;
            String key = resolveSymbolKey(node, depth);
            if (key != null && !key.isBlank()) {
                votes.merge(key, 1, Integer::sum);
            }
        }

        if (votes.isEmpty()) {
            return SUPER_MISC_KEY;
        }

        // 다수결; 동수 시 사전식 최솟값 (결정적 정렬 보장)
        return votes.entrySet().stream()
                .max(Comparator.comparingInt(Map.Entry<String, Integer>::getValue)
                        .thenComparing(Comparator.comparing(Map.Entry<String, Integer>::getKey,
                                Comparator.reverseOrder())))
                .map(Map.Entry::getKey)
                .orElse(SUPER_MISC_KEY);
    }

    /** [1] moduleId 우선, [2] packageRoot prefix fallback. */
    private String resolveSymbolKey(ProjectedNode node, int depth) {
        String moduleId = node.getModuleId();
        if (moduleId != null && !moduleId.isBlank()) {
            return moduleId;
        }
        return extractPackagePrefix(node.getPackageName(), depth);
    }

    private String extractPackagePrefix(String packageName, int depth) {
        if (packageName == null || packageName.isBlank()) return null;
        String[] parts = packageName.split("\\.");
        int len = Math.min(depth, parts.length);
        return len == 0 ? null : String.join(".", Arrays.copyOfRange(parts, 0, len));
    }

    // ── SuperSubsystem 생성 ───────────────────────────────────────────────────

    private SuperSubsystem buildSuperSubsystem(String canonicalKey, List<Subsystem> subs, MergeContext ctx) {
        List<String> memberSubsystemIds = subs.stream()
                .map(Subsystem::getSubsystemId)
                .toList();

        List<String> memberSymbolIds = subs.stream()
                .flatMap(s -> s.getMemberSymbolIds().stream())
                .distinct()
                .toList();

        List<String> packageRoots = subs.stream()
                .flatMap(s -> s.getPackageRoots().stream())
                .distinct()
                .sorted()
                .toList();

        return SuperSubsystem.builder()
                .superSubsystemId(null)
                .canonicalKey(canonicalKey)
                .displayName(resolveDisplayName(canonicalKey, ctx.displayNames()))
                .memberSubsystemIds(memberSubsystemIds)
                .memberSymbolIds(memberSymbolIds)
                .memberCount(memberSymbolIds.size())
                .moduleAffinity(computeAffinity(memberSymbolIds, ctx))
                .packageRoots(packageRoots)
                .build();
    }

    /** moduleId → 노드 비율 (합 = 1.0). value 내림차순, key 오름차순 정렬. */
    private Map<String, Double> computeAffinity(List<String> symbolIds, MergeContext ctx) {
        int depth = ctx.config().getFallback().getPackageRootCommonPrefixDepth();
        Map<String, Integer> counts = new HashMap<>();
        int total = 0;

        for (String symbolId : symbolIds) {
            ProjectedNode node = ctx.nodeIndex().get(symbolId);
            if (node == null) continue;
            String key = resolveSymbolKey(node, depth);
            if (key != null && !key.isBlank()) {
                counts.merge(key, 1, Integer::sum);
                total++;
            }
        }

        if (total == 0) return Map.of();

        int finalTotal = total;
        Map<String, Double> affinity = new LinkedHashMap<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .forEach(e -> affinity.put(e.getKey(), (double) e.getValue() / finalTotal));
        return affinity;
    }

    private String resolveDisplayName(String canonicalKey, Map<String, String> displayNames) {
        String displayName = displayNames.get(canonicalKey);
        if (displayName != null && !displayName.isBlank()) return displayName;
        return canonicalKey;
    }

    // ── 소형 그룹 흡수 ────────────────────────────────────────────────────────

    private List<SuperSubsystem> absorb(
            List<SuperSubsystem> large, List<SuperSubsystem> small, MergeContext ctx) {
        if (small.isEmpty()) return new ArrayList<>(large);

        Set<String> largeKeys = large.stream()
                .map(SuperSubsystem::getCanonicalKey)
                .collect(Collectors.toSet());

        // 소형 그룹을 부모 키 매핑 또는 misc 누적
        Map<String, List<SuperSubsystem>> toAbsorb = new HashMap<>();
        List<SuperSubsystem> miscAccum = new ArrayList<>();

        for (SuperSubsystem s : small) {
            Optional<String> parentKey = findParentKey(s.getCanonicalKey(), largeKeys);
            if (parentKey.isPresent()) {
                toAbsorb.computeIfAbsent(parentKey.get(), k -> new ArrayList<>()).add(s);
            } else {
                miscAccum.add(s);
            }
        }

        List<SuperSubsystem> result = new ArrayList<>();
        for (SuperSubsystem l : large) {
            List<SuperSubsystem> absorbed = toAbsorb.getOrDefault(l.getCanonicalKey(), List.of());
            result.add(absorbed.isEmpty() ? l : mergeInto(l, absorbed, ctx));
        }

        if (!miscAccum.isEmpty()) {
            result.add(buildMisc(miscAccum, ctx));
        }

        return result;
    }

    /**
     * canonicalKey의 부모 키를 찾는다.
     * - 모듈 ID (`-` 구분): "junit-jupiter-params" → "junit-jupiter" → "junit" 순으로 탐색
     * - 패키지 기반 (`.` 구분): "org.junit.jupiter.api" → "org.junit.jupiter" 순으로 탐색
     */
    private Optional<String> findParentKey(String key, Set<String> candidateKeys) {
        if (key == null || key.isBlank() || SUPER_MISC_KEY.equals(key)) {
            return Optional.empty();
        }
        boolean dotBased = key.contains(".");
        String sep = dotBased ? "." : "-";
        String[] parts = key.split(dotBased ? "\\." : "-");

        for (int len = parts.length - 1; len > 0; len--) {
            String prefix = String.join(sep, Arrays.copyOfRange(parts, 0, len));
            if (candidateKeys.contains(prefix)) {
                return Optional.of(prefix);
            }
        }
        return Optional.empty();
    }

    private SuperSubsystem mergeInto(SuperSubsystem base, List<SuperSubsystem> absorbed, MergeContext ctx) {
        List<String> allSubsystemIds = new ArrayList<>(base.getMemberSubsystemIds());
        absorbed.forEach(a -> allSubsystemIds.addAll(a.getMemberSubsystemIds()));

        List<String> allSymbolIds = Stream.concat(
                        base.getMemberSymbolIds().stream(),
                        absorbed.stream().flatMap(a -> a.getMemberSymbolIds().stream()))
                .distinct()
                .toList();

        List<String> allPackageRoots = Stream.concat(
                        base.getPackageRoots().stream(),
                        absorbed.stream().flatMap(a -> a.getPackageRoots().stream()))
                .distinct()
                .sorted()
                .toList();

        return base.toBuilder()
                .memberSubsystemIds(allSubsystemIds)
                .memberSymbolIds(allSymbolIds)
                .memberCount(allSymbolIds.size())
                .moduleAffinity(computeAffinity(allSymbolIds, ctx))
                .packageRoots(allPackageRoots)
                .build();
    }

    private SuperSubsystem buildMisc(List<SuperSubsystem> smalls, MergeContext ctx) {
        List<String> memberSubsystemIds = smalls.stream()
                .flatMap(s -> s.getMemberSubsystemIds().stream())
                .toList();
        List<String> memberSymbolIds = smalls.stream()
                .flatMap(s -> s.getMemberSymbolIds().stream())
                .distinct()
                .toList();
        List<String> packageRoots = smalls.stream()
                .flatMap(s -> s.getPackageRoots().stream())
                .distinct()
                .sorted()
                .toList();

        return SuperSubsystem.builder()
                .superSubsystemId(null)
                .canonicalKey(SUPER_MISC_KEY)
                .displayName(SUPER_MISC_KEY)
                .memberSubsystemIds(memberSubsystemIds)
                .memberSymbolIds(memberSymbolIds)
                .memberCount(memberSymbolIds.size())
                .moduleAffinity(computeAffinity(memberSymbolIds, ctx))
                .packageRoots(packageRoots)
                .build();
    }

    // ── ID 할당 ───────────────────────────────────────────────────────────────

    /** super_misc을 맨 뒤로, 나머지는 canonicalKey 사전순 정렬 후 sup_NNN 부여. */
    private List<SuperSubsystem> assignIds(List<SuperSubsystem> superSubsystems) {
        List<SuperSubsystem> sorted = superSubsystems.stream()
                .sorted(Comparator
                        .comparingInt((SuperSubsystem s) -> SUPER_MISC_KEY.equals(s.getCanonicalKey()) ? 1 : 0)
                        .thenComparing(SuperSubsystem::getCanonicalKey))
                .collect(Collectors.toList());

        List<SuperSubsystem> result = new ArrayList<>();
        int seq = 1;
        for (SuperSubsystem s : sorted) {
            result.add(s.toBuilder()
                    .superSubsystemId(String.format("sup_%03d", seq++))
                    .build());
        }
        return result;
    }
}
