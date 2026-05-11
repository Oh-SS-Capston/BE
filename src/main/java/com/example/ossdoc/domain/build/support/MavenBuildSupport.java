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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 역할:
 * Maven 실행/출력 해석을 담당하는 실행 지원 컴포넌트.
 *
 * 책임:
 * 1) compile/classpath 명령 실행
 * 2) wrapper 실패 시 시스템 mvn 폴백
 * 3) 모듈 매니페스트 및 classpath 파일 파싱 보조
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MavenBuildSupport {

    private final ProcessRunner processRunner;
    private final BuildCommandProperties buildCommandProperties;
    private final BuildPathNormalizer buildPathNormalizer;

    /**
     * 역할:
     * 테스트를 제외한 Maven 메인 컴파일(compile)을 수행한다.
     *
     * 책임:
     * mavenLocalRepoPath와 환경변수를 반영해 실행 결과를 반환한다.
     */
    public ProcessRunner.Result compile(Path repoRoot, Path mavenLocalRepoPath) {
        return compile(repoRoot, mavenLocalRepoPath, null);
    }

    /**
     * 역할:
     * Maven 컴파일 실행의 내부 진입점.
     *
     * 책임:
     * 실행 인자 조립 및 runMavenCommand 위임을 담당한다.
     */
    public ProcessRunner.Result compile(Path repoRoot, Path mavenLocalRepoPath, Map<String, String> environmentOverrides) {
        List<String> args = new ArrayList<>();
        args.add("-q");
        args.add("-DskipTests");
        // 격리 실행 모드에서는 Run 전용 로컬 저장소를 사용해 의존성 충돌을 줄인다.
        appendMavenLocalRepoOption(args, mavenLocalRepoPath);
        // build_manifest의 classesDirs(target/classes) 기준에 맞춰 메인 소스만 컴파일한다.
        // test-compile 대신 compile을 사용해 테스트 소스 컴파일 비용을 줄인다.
        args.add("compile");

        log.info("[BUILD] Maven compile start. repoRoot={}", repoRoot);
        return runMavenCommand(repoRoot, args, Duration.ofMinutes(20), environmentOverrides);
    }

    /**
     * 역할:
     * dependency:build-classpath를 실행해 classpath 파일을 생성한다.
     *
     * 책임:
     * build 단계에서 타입 해석에 필요한 의존성 경로 확보를 지원한다.
     */
    public ProcessRunner.Result buildClasspath(Path repoRoot, Path outputFile, Path mavenLocalRepoPath) {
        return buildClasspath(repoRoot, outputFile, mavenLocalRepoPath, null);
    }

    public ProcessRunner.Result buildClasspath(Path repoRoot,
                                               Path outputFile,
                                               Path mavenLocalRepoPath,
                                               Map<String, String> environmentOverrides) {
        List<String> args = new ArrayList<>();
        args.add("-q");
        args.add("-DskipTests");
        // classpath 산출도 compile과 동일하게 격리 저장소를 사용한다.
        appendMavenLocalRepoOption(args, mavenLocalRepoPath);
        args.add("dependency:build-classpath");
        args.add("-Dmdep.outputFile=" + outputFile);

        log.info("[BUILD] Maven classpath build start. repoRoot={}, output={}", repoRoot, outputFile);
        return runMavenCommand(repoRoot, args, Duration.ofMinutes(10), environmentOverrides);
    }

    /**
     * 역할:
     * Maven 모듈 루트 경로를 BuildModuleManifest로 변환한다.
     *
     * 책임:
     * source/test/resource/classes/classpath 정보를 표준 DTO로 매핑한다.
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

        // classpath/runtimeClasspath은 절대경로로 정규화해 후속 분석 단계에서 OS 의존성을 줄인다.
        return BuildModuleManifest.builder()
                .moduleId(moduleId)
                .name(name)
                .sourceRoots(sourceRoots)
                .testRoots(testRoots)
                .resourceRoots(resourceRoots)
                .classesDirs(classesDirs)
                .compileClasspath(buildPathNormalizer.toAbsolutePaths(repoRoot, classpath))
                .runtimeClasspath(buildPathNormalizer.toAbsolutePaths(repoRoot, classpath))
                .status(classesDirs.isEmpty() ? "PARTIAL" : "OK")
                .failReason(null)
                .build();
    }

    /**
     * 역할:
     * Maven classpath 출력 파일을 읽어 경로 목록으로 반환한다.
     *
     * 책임:
     * OS path separator 분리 + 절대경로 정규화 + 예외 안전 처리.
     */
    public List<String> readClasspathFile(Path repoRoot, Path outputFile) {
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
                    // 상대경로/절대경로 혼재를 방지하기 위해 한 번 더 절대경로로 통일한다.
                    String normalized = buildPathNormalizer.toAbsolutePath(repoRoot, entry);
                    if (normalized != null) {
                        result.add(normalized);
                    }
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("[BUILD] Failed to read classpath file. file={}", outputFile, e);
            return List.of();
        }
    }

    private ProcessRunner.Result runMavenCommand(Path repoRoot,
                                                 List<String> args,
                                                 Duration timeout,
                                                 Map<String, String> environmentOverrides) {
        String primaryCommand = selectMavenCmd(repoRoot);
        List<String> primary = new ArrayList<>();
        primary.add(primaryCommand);
        primary.addAll(args);

        ProcessRunner.Result first = processRunner.run(repoRoot, primary, timeout, environmentOverrides);
        if (first.getExitCode() == 0) {
            return first;
        }

        if (!isWrapperCommand(primaryCommand) || !isWrapperBootstrapFailure(first)) {
            return first;
        }

        // wrapper 실행 자체가 실패하면 시스템 mvn 명령으로 1회 폴백한다.
        String fallbackCommand = buildCommandProperties.getMavenCommand();
        if (fallbackCommand == null || fallbackCommand.isBlank() || Objects.equals(primaryCommand, fallbackCommand)) {
            return first;
        }

        List<String> fallback = new ArrayList<>();
        fallback.add(fallbackCommand);
        fallback.addAll(args);

        log.warn("[BUILD] Maven wrapper command failed to bootstrap. fallbackCommand={}", fallbackCommand);
        ProcessRunner.Result fallbackResult = processRunner.run(repoRoot, fallback, timeout, environmentOverrides);
        if (fallbackResult.getExitCode() == 0) {
            log.info("[BUILD] Maven fallback command succeeded. command={}", fallbackCommand);
        }
        return fallbackResult;
    }

    private boolean isWrapperCommand(String command) {
        String normalized = command == null ? "" : command.replace("\\", "/").toLowerCase(Locale.ROOT);
        return normalized.endsWith("/mvnw")
                || normalized.endsWith("/mvnw.cmd")
                || normalized.equals("mvnw")
                || normalized.equals("mvnw.cmd")
                || normalized.equals("./mvnw");
    }

    private boolean isWrapperBootstrapFailure(ProcessRunner.Result result) {
        String joined = ((result.getError() == null ? "" : result.getError()) + "\n"
                + (result.getOutput() == null ? "" : result.getOutput())).toLowerCase(Locale.ROOT);

        return joined.contains("cannot run program")
                || joined.contains("createprocess error=2")
                || joined.contains("is not recognized as an internal or external command")
                || joined.contains("command not found")
                || joined.contains("no such file or directory");
    }

    private void addIfExists(List<String> target, Path path) {
        if (Files.exists(path) && Files.isDirectory(path)) {
            target.add(buildPathNormalizer.normalize(path));
        }
    }

    private String toModuleId(Path repoRoot, Path moduleRoot) {
        if (repoRoot.equals(moduleRoot)) {
            return ".";
        }
        return repoRoot.relativize(moduleRoot).toString().replace("\\", "/");
    }

    private String selectMavenCmd(Path repoRoot) {
        Path mvnwCmd = repoRoot.resolve("mvnw.cmd");
        if (Files.exists(mvnwCmd)) {
            // wrapper는 절대경로로 호출해 ProcessBuilder 경로 탐색 이슈를 줄인다.
            return mvnwCmd.toAbsolutePath().normalize().toString();
        }

        Path mvnw = repoRoot.resolve("mvnw");
        if (Files.exists(mvnw)) {
            // Windows/Unix 공통으로 wrapper 절대경로 우선
            return mvnw.toAbsolutePath().normalize().toString();
        }

        return buildCommandProperties.getMavenCommand();
    }

    private void appendMavenLocalRepoOption(List<String> command, Path mavenLocalRepoPath) {
        if (mavenLocalRepoPath == null) {
            return;
        }
        // -Dmaven.repo.local 옵션으로 Run별 캐시 경로를 강제한다.
        command.add("-Dmaven.repo.local=" + buildPathNormalizer.normalize(mavenLocalRepoPath));
    }
}
