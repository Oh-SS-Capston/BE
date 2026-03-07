package com.example.ossdoc.domain.build.support;

import com.example.ossdoc.domain.build.dto.json.BuildModuleManifest;
import com.example.ossdoc.global.config.BuildCommandProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class MavenBuildSupport {

    private final ProcessRunner processRunner;
    private final BuildCommandProperties buildCommandProperties;

    /**
     * Maven 컴파일 실행
     * - 테스트는 스킵하고 compile까지만 가볍게 수행
     */
    public ProcessRunner.Result compile(Path repoRoot) {
        log.info("mvn path={}", repoRoot);
        List<String> cmd = List.of(
                selectMavenCmd(repoRoot),
                "-q",
                "-DskipTests",
                "test-compile"
        );

        log.info("[BUILD] Maven compile start. repoRoot={}", repoRoot);
        return processRunner.run(repoRoot, cmd, Duration.ofMinutes(20));
    }

    /**
     * dependency:build-classpath 로 classpath 파일 생성
     */
    public ProcessRunner.Result buildClasspath(Path repoRoot, Path outputFile) {
        List<String> cmd = List.of(
                selectMavenCmd(repoRoot),
                "-q",
                "-DskipTests",
                "dependency:build-classpath",
                "-Dmdep.outputFile=" + outputFile.toString()
        );

        log.info("[BUILD] Maven classpath build start. repoRoot={}, output={}", repoRoot, outputFile);
        return processRunner.run(repoRoot, cmd, Duration.ofMinutes(10));
    }

    /**
     * 모듈 루트 기준으로 BuildModuleManifest를 생성
     * - sourceRoots/testRoots/resourceRoots
     * - classesDirs
     * - compileClasspath/runtimeClasspath
     */
    public BuildModuleManifest toModuleManifest(Path repoRoot, Path moduleRoot, List<String> classpath) {
        List<String> sourceRoots = new ArrayList<>();
        List<String> testRoots = new ArrayList<>();
        List<String> resourceRoots = new ArrayList<>();
        List<String> classesDirs = new ArrayList<>();

        addIfExists(sourceRoots, moduleRoot.resolve("src/main/java"));
        addIfExists(testRoots, moduleRoot.resolve("src/test/java"));
        addIfExists(resourceRoots, moduleRoot.resolve("src/main/resources"));
        addIfExists(classesDirs, moduleRoot.resolve("target/classes"));

        String moduleId = toModuleId(repoRoot, moduleRoot);
        String name = moduleRoot.getFileName() != null ? moduleRoot.getFileName().toString() : moduleId;

        return BuildModuleManifest.builder()
                .moduleId(moduleId)
                .name(name)
                .sourceRoots(sourceRoots)
                .testRoots(testRoots)
                .resourceRoots(resourceRoots)
                .classesDirs(classesDirs)
                .compileClasspath(classpath)
                .runtimeClasspath(classpath)
                .status(classesDirs.isEmpty() ? "PARTIAL" : "OK")
                .failReason(null)
                .build();
    }

    public List<String> readClasspathFile(Path outputFile) {
        try {
            if (!Files.exists(outputFile)) {
                return List.of();
            }

            String line = Files.readString(outputFile).trim();
            if (line.isBlank()) {
                return List.of();
            }

            String[] entries = line.split(System.getProperty("path.separator"));
            List<String> result = new ArrayList<>();
            for (String entry : entries) {
                if (!entry.isBlank()) {
                    result.add(entry.trim());
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("[BUILD] Failed to read classpath file. file={}", outputFile, e);
            return List.of();
        }
    }

    private void addIfExists(List<String> target, Path path) {
        if (Files.exists(path) && Files.isDirectory(path)) {
            target.add(path.toString());
        }
    }

    private String toModuleId(Path repoRoot, Path moduleRoot) {
        if (repoRoot.equals(moduleRoot)) {
            return ".";
        }
        return repoRoot.relativize(moduleRoot).toString().replace("\\", "/");
    }

    private String selectMavenCmd(Path repoRoot) {
        if (Files.exists(repoRoot.resolve("mvnw.cmd"))) return "mvnw.cmd";
        if (Files.exists(repoRoot.resolve("mvnw"))) return "./mvnw";
        return buildCommandProperties.getMavenCommand();
    }

}