package com.example.ossdoc.domain.cluster.support.supercluster;

import com.example.ossdoc.domain.build.dto.json.BuildModuleManifest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Option 3: BUILD_MANIFEST 기반 symbol → 모듈 메모리 매칭기 (DB 무변경).
 *
 * <p>graph의 {@code SymbolEntity.module}은 {@code FactsSymbolConverter}에서 null로 적재되므로,
 * 빌드 매니페스트의 {@code sourceRoots ↔ moduleId} 정보로 symbol의 모듈을 메모리에서 복원한다.
 *
 * <p>매칭 키는 sourceRoot 경로다:
 * <ul>
 *   <li>매니페스트 sourceRoot: 절대경로 {@code C:/.../repo/resilience4j-core/src/main/java}</li>
 *   <li>symbol sourceRoot: 상대경로 {@code resilience4j-core/src/main/java}</li>
 * </ul>
 * 둘 다 {@code /repo/} 이후의 상대 경로로 정규화해 비교한다.
 */
public final class ModuleResolver {

    /** 정규화된 sourceRoot → moduleKey (앞 ':' 제거된 moduleId). */
    private final Map<String, String> sourceRootToModuleKey;

    /** moduleKey → displayName (ModuleEntity.name 우선, 없으면 moduleKey). */
    private final Map<String, String> displayNames;

    private ModuleResolver(Map<String, String> sourceRootToModuleKey, Map<String, String> displayNames) {
        this.sourceRootToModuleKey = sourceRootToModuleKey;
        this.displayNames = displayNames;
    }

    public static ModuleResolver empty() {
        return new ModuleResolver(Map.of(), Map.of());
    }

    public static ModuleResolver from(List<BuildModuleManifest> modules) {
        if (modules == null || modules.isEmpty()) {
            return empty();
        }

        Map<String, String> sourceRootToModuleKey = new LinkedHashMap<>();
        Map<String, String> displayNames = new LinkedHashMap<>();

        for (BuildModuleManifest module : modules) {
            String moduleKey = normalizeModuleKey(module.getModuleId());
            if (moduleKey == null) continue;

            String displayName = (module.getName() != null && !module.getName().isBlank())
                    ? module.getName()
                    : moduleKey;
            displayNames.put(moduleKey, displayName);

            registerRoots(sourceRootToModuleKey, module.getSourceRoots(), moduleKey);
            registerRoots(sourceRootToModuleKey, module.getTestRoots(), moduleKey);
        }

        return new ModuleResolver(sourceRootToModuleKey, displayNames);
    }

    private static void registerRoots(Map<String, String> target, List<String> roots, String moduleKey) {
        if (roots == null) return;
        for (String root : roots) {
            String norm = normalizeSourceRoot(root);
            if (norm != null && !norm.isBlank()) {
                // 동일 sourceRoot가 여러 모듈에 잡히는 비정상 케이스는 먼저 등록된 모듈을 유지한다.
                target.putIfAbsent(norm, moduleKey);
            }
        }
    }

    /**
     * symbol의 sourceRoot로 소속 모듈 키를 찾는다.
     * 매칭 실패 시 null → 호출부에서 packageRoot fallback으로 처리된다.
     */
    public String resolveModuleKey(String symbolSourceRoot) {
        String norm = normalizeSourceRoot(symbolSourceRoot);
        if (norm == null) return null;
        return sourceRootToModuleKey.get(norm);
    }

    /** canonicalKey(moduleKey) → displayName 매핑 (전략의 displayName 조회용). */
    public Map<String, String> displayNames() {
        return displayNames;
    }

    public int moduleCount() {
        return displayNames.size();
    }

    /** 앞쪽 ':' (gradle projectPath 구분자)를 제거해 moduleKey로 정규화. ":resilience4j-core" → "resilience4j-core". */
    private static String normalizeModuleKey(String moduleId) {
        if (moduleId == null || moduleId.isBlank()) return null;
        String key = moduleId.trim();
        while (key.startsWith(":")) {
            key = key.substring(1);
        }
        return key.isBlank() ? null : key;
    }

    /**
     * sourceRoot 경로를 {@code /repo/} 이후 상대 경로로 정규화한다.
     * - 매니페스트(절대): {@code .../repo/resilience4j-core/src/main/java} → {@code resilience4j-core/src/main/java}
     * - symbol(상대): {@code resilience4j-core/src/main/java} → 그대로
     */
    private static String normalizeSourceRoot(String path) {
        if (path == null || path.isBlank()) return null;
        String norm = path.replace('\\', '/').trim();
        int idx = norm.lastIndexOf("/repo/");
        if (idx >= 0) {
            norm = norm.substring(idx + "/repo/".length());
        }
        while (norm.startsWith("/")) {
            norm = norm.substring(1);
        }
        return norm.isBlank() ? null : norm;
    }
}
