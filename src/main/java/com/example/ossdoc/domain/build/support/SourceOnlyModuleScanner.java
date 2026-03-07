package com.example.ossdoc.domain.build.support;

import com.example.ossdoc.domain.build.dto.json.BuildModuleManifest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class SourceOnlyModuleScanner {

    /**
     * SOURCE_ONLY 폴백용 스캐너
     *
     * 목표:
     * 1. 빌드 실패/덤프 실패 시에도 AST가 돌 수 있게 sourceRoots를 확보한다.
     * 2. repoRoot 자체 + 바로 아래 하위 디렉토리들을 모듈 후보로 본다.
     * 3. classesDirs/classpath는 SOURCE_ONLY 단계라 비워둔다.
     */
    public List<BuildModuleManifest> scan(Path repoRoot) {
        List<BuildModuleManifest> modules = new ArrayList<>();

        // 1) repoRoot 자체를 단일 모듈 후보로 먼저 본다.
        BuildModuleManifest rootModule = scanSingleModule(repoRoot, ".", resolveModuleName(repoRoot, "."));
        if (hasAnyRoots(rootModule)) {
            modules.add(rootModule);
        }

        // 2) 바로 아래 하위 디렉토리들을 멀티모듈 후보로 본다.
        try (var stream = Files.list(repoRoot)) {
            stream.filter(Files::isDirectory)
                    .forEach(dir -> {
                        BuildModuleManifest childModule = scanSingleModule(
                                dir,
                                dir.getFileName().toString(),
                                resolveModuleName(dir, dir.getFileName().toString())
                        );

                        if (hasAnyRoots(childModule)) {
                            modules.add(childModule);
                        }
                    });
        } catch (Exception e) {
            log.warn("[BUILD] SOURCE_ONLY scanner failed to inspect subdirectories. repoRoot={}", repoRoot, e);
        }

        log.info("[BUILD] SOURCE_ONLY scanner result. repoRoot={}, moduleCount={}", repoRoot, modules.size());
        return modules;
    }

    private BuildModuleManifest scanSingleModule(Path moduleRoot, String moduleId, String moduleName) {
        List<String> sourceRoots = new ArrayList<>();
        List<String> testRoots = new ArrayList<>();
        List<String> resourceRoots = new ArrayList<>();

        addIfExists(sourceRoots, moduleRoot, "src/main/java");
        addIfExists(testRoots, moduleRoot, "src/test/java");
        addIfExists(resourceRoots, moduleRoot, "src/main/resources");

        // 필요 시 확장 가능
        // addIfExists(sourceRoots, moduleRoot, "src/main/kotlin");
        // addIfExists(testRoots, moduleRoot, "src/test/kotlin");

        return BuildModuleManifest.builder()
                .moduleId(moduleId)
                .name(moduleName)
                .sourceRoots(sourceRoots)
                .testRoots(testRoots)
                .resourceRoots(resourceRoots)
                .classesDirs(List.of())
                .compileClasspath(List.of())
                .runtimeClasspath(List.of())
                .status(hasAnyRoots(sourceRoots, testRoots, resourceRoots) ? "PARTIAL" : "FAILED")
                .failReason(null)
                .build();
    }

    private void addIfExists(List<String> target, Path moduleRoot, String relativePath) {
        Path candidate = moduleRoot.resolve(relativePath);
        if (Files.exists(candidate) && Files.isDirectory(candidate)) {
            target.add(candidate.toString());
        }
    }

    private boolean hasAnyRoots(BuildModuleManifest module) {
        return hasAnyRoots(module.getSourceRoots(), module.getTestRoots(), module.getResourceRoots());
    }

    private boolean hasAnyRoots(List<String> sourceRoots, List<String> testRoots, List<String> resourceRoots) {
        return !sourceRoots.isEmpty() || !testRoots.isEmpty() || !resourceRoots.isEmpty();
    }

    private String resolveModuleName(Path moduleRoot, String moduleId) {
        if (".".equals(moduleId)) {
            return moduleRoot.getFileName() != null ? moduleRoot.getFileName().toString() : "root";
        }
        return moduleRoot.getFileName() != null ? moduleRoot.getFileName().toString() : moduleId;
    }
}