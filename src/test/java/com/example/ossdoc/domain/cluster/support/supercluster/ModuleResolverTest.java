package com.example.ossdoc.domain.cluster.support.supercluster;

import com.example.ossdoc.domain.build.dto.json.BuildModuleManifest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ModuleResolverTest {

    private BuildModuleManifest module(String moduleId, String name, List<String> sourceRoots) {
        return BuildModuleManifest.builder()
                .moduleId(moduleId)
                .name(name)
                .sourceRoots(sourceRoots)
                .build();
    }

    @Test
    @DisplayName("절대경로 매니페스트 sourceRoot ↔ 상대경로 symbol sourceRoot 매칭")
    void matchesAbsoluteManifestToRelativeSymbolRoot() {
        ModuleResolver resolver = ModuleResolver.from(List.of(
                module(":resilience4j-core", "resilience4j-core",
                        List.of("C:/data/ossdoc/run_x/repo/resilience4j-core/src/main/java")),
                module(":resilience4j-bulkhead", "resilience4j-bulkhead",
                        List.of("C:/data/ossdoc/run_x/repo/resilience4j-bulkhead/src/main/java"))));

        // symbol의 sourceRoot는 facts 기준 상대경로
        assertThat(resolver.resolveModuleKey("resilience4j-core/src/main/java"))
                .isEqualTo("resilience4j-core");
        assertThat(resolver.resolveModuleKey("resilience4j-bulkhead/src/main/java"))
                .isEqualTo("resilience4j-bulkhead");
    }

    @Test
    @DisplayName("moduleId 앞 ':' 제거 + name을 displayName으로 노출")
    void stripsColonAndExposesDisplayName() {
        ModuleResolver resolver = ModuleResolver.from(List.of(
                module(":resilience4j-core", "resilience4j-core",
                        List.of("C:/repo/resilience4j-core/src/main/java"))));

        assertThat(resolver.displayNames()).containsEntry("resilience4j-core", "resilience4j-core");
        assertThat(resolver.moduleCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("name이 비면 moduleKey를 displayName으로 사용")
    void usesModuleKeyWhenNameBlank() {
        ModuleResolver resolver = ModuleResolver.from(List.of(
                module(":core", null, List.of("C:/repo/core/src/main/java"))));

        assertThat(resolver.displayNames()).containsEntry("core", "core");
    }

    @Test
    @DisplayName("매칭 실패/빈 resolver → null 반환 (packageRoot fallback 유도)")
    void returnsNullWhenNoMatch() {
        ModuleResolver resolver = ModuleResolver.from(List.of(
                module(":core", "core", List.of("C:/repo/core/src/main/java"))));

        assertThat(resolver.resolveModuleKey("other-module/src/main/java")).isNull();
        assertThat(resolver.resolveModuleKey(null)).isNull();
        assertThat(ModuleResolver.empty().resolveModuleKey("core/src/main/java")).isNull();
    }

    @Test
    @DisplayName("백슬래시 경로도 정규화해 매칭")
    void normalizesBackslashPaths() {
        ModuleResolver resolver = ModuleResolver.from(List.of(
                module(":core", "core",
                        List.of("C:\\data\\ossdoc\\run_x\\repo\\core\\src\\main\\java"))));

        assertThat(resolver.resolveModuleKey("core/src/main/java")).isEqualTo("core");
    }
}
