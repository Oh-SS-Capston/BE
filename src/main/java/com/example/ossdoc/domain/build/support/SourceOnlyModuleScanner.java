package com.example.ossdoc.domain.build.support;

import com.example.ossdoc.domain.build.dto.json.BuildModuleManifest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Slf4j
@Component
@RequiredArgsConstructor
public class SourceOnlyModuleScanner {

    // src/main/java가 없을 때만 검사할 비표준 소스 루트 후보 (우선순위 순)
    private static final List<String> SOURCE_FALLBACK_DIRS = List.of("src", "source", "java");
    // 비표준 src를 소스 루트로 잡을 때 main 소스로 보지 않을 최상위 하위 디렉터리
    private static final List<String> NON_SOURCE_SUBDIRS = List.of("test", "it", "generated");

    private final BuildPathNormalizer buildPathNormalizer;

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

        // 1) 표준 레이아웃 우선. src/main/java가 있으면 그것만 사용한다.
        //    (src까지 같이 잡으면 표준 프로젝트에서 src/test가 main에 섞이므로 추가하지 않는다.)
        boolean standardSourceFound = addIfExists(sourceRoots, moduleRoot, "src/main/java");

        // 2) src/main/java가 없을 때만 비표준 레이아웃(guava의 src 등)을 폴백으로 검사한다.
        if (!standardSourceFound) {
            addFirstSourceFallback(sourceRoots, moduleRoot);
        }

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

    private boolean addIfExists(List<String> target, Path moduleRoot, String relativePath) {
        Path candidate = moduleRoot.resolve(relativePath);
        if (Files.exists(candidate) && Files.isDirectory(candidate)) {
            target.add(buildPathNormalizer.normalize(candidate));
            return true;
        }
        return false;
    }

    /**
     * 표준 src/main/java가 없을 때 비표준 소스 루트 후보(src/source/java)를 우선순위대로 검사해
     * 실제 main 소스(.java)가 있는 "첫" 후보만 sourceRoot로 추가한다.
     */
    private void addFirstSourceFallback(List<String> target, Path moduleRoot) {
        for (String candidateName : SOURCE_FALLBACK_DIRS) {
            Path candidate = moduleRoot.resolve(candidateName);
            if (Files.exists(candidate) && Files.isDirectory(candidate) && hasMainJavaFiles(candidate)) {
                target.add(buildPathNormalizer.normalize(candidate));
                return; // 첫 후보만 사용해 과대 탐색을 막는다.
            }
        }
    }

    /**
     * root 하위에 .java 파일이 존재하되, test/it/generated 전용이 아닌 main 소스가 있는지 확인한다.
     */
    private boolean hasMainJavaFiles(Path root) {
        try (var stream = Files.walk(root)) {
            return stream
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(Files::isRegularFile)
                    .anyMatch(p -> !isUnderNonSourceSubdir(root, p));
        } catch (Exception e) {
            log.warn("[BUILD] SOURCE_ONLY scanner failed to inspect java files. root={}", root, e);
            return false;
        }
    }

    private boolean isUnderNonSourceSubdir(Path root, Path file) {
        Path relative = root.relativize(file);
        if (relative.getNameCount() == 0) {
            return false;
        }
        String firstSegment = relative.getName(0).toString().toLowerCase(Locale.ROOT);
        return NON_SOURCE_SUBDIRS.contains(firstSegment);
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
