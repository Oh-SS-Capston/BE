package com.example.ossdoc.domain.build.support;

import com.example.ossdoc.domain.build.enums.BuildToolKind;
import com.example.ossdoc.global.config.BuildCommandProperties;
import com.example.ossdoc.global.properties.WorkspaceProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 역할:
 * Gradle 실행 전략(명령 선택, 재시도, wrapper 폴백)을 담당한다.
 *
 * 책임:
 * 1) wrapper 우선/절대경로 기반 명령 결정
 * 2) JAVA_HOME 후보 재시도 실행
 * 3) wrapper 부트스트랩 실패 시 시스템 gradle 1회 폴백
 * 4) 격리 실행 환경(GRADLE_USER_HOME) 구성
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GradleBuildSupport {

    private final ProcessRunner processRunner;
    private final BuildCommandProperties buildCommandProperties;
    private final BuildToolchainSupport buildToolchainSupport;
    private final WorkspaceProperties workspaceProperties;

    /**
     * 역할:
     * 현재 저장소에서 사용할 Gradle 실행 명령을 결정한다.
     *
     * 책임:
     * gradlew(.bat) 존재 시 절대경로 wrapper를 우선 사용하고, 없으면 시스템 gradle을 사용한다.
     */
    public String selectGradleCmd(Path repoRoot) {
        // wrapper는 절대경로로 호출해 ProcessBuilder 경로 탐색 이슈를 줄인다.
        Path gradlewBat = repoRoot.resolve("gradlew.bat");
        if (Files.exists(gradlewBat)) return gradlewBat.toAbsolutePath().normalize().toString();

        Path gradlew = repoRoot.resolve("gradlew");
        if (Files.exists(gradlew)) return gradlew.toAbsolutePath().normalize().toString();

        return buildCommandProperties.getGradleCommand();
    }

    /**
     * 역할:
     * Gradle 명령을 실행하고 Java 호환성 문제 시 후보 JDK로 재시도한다.
     *
     * 책임:
     * 1) 1순위 JAVA_HOME 실행
     * 2) 호환성 신호 감지 시 순차 재시도
     * 3) 필요 시 현재 런타임 JVM baseline 폴백
     */
    public ProcessRunner.Result runWithJavaFallback(Path repoRoot,
                                                    Path workspaceRoot,
                                                    List<String> command,
                                                    Duration timeout,
                                                    String stage) {
        Map<String, String> baseEnvironment = resolveGradleBaseEnvironment(workspaceRoot);
        List<String> candidates = buildToolchainSupport.resolveJavaHomeCandidatesForGradle(repoRoot);

        ProcessRunner.Result first;
        String selectedJavaHome = null;
        if (candidates.isEmpty()) {
            first = runGradleCommand(repoRoot, command, timeout, baseEnvironment);
        } else {
            selectedJavaHome = candidates.get(0);
            first = runGradleCommand(
                    repoRoot,
                    command,
                    timeout,
                    buildToolchainSupport.buildEnvironment(baseEnvironment, selectedJavaHome)
            );
            log.info("[BUILD] Gradle {} initial JAVA_HOME={} (version-aware selection)", stage, selectedJavaHome);
        }

        if (first.getExitCode() == 0) {
            return first;
        }

        Optional<String> compatibilityReason = buildToolchainSupport.detectJavaCompatibilityReason(BuildToolKind.GRADLE, first);
        if (compatibilityReason.isEmpty()) {
            if (selectedJavaHome != null && !buildToolchainSupport.isCurrentRuntimeJavaHome(selectedJavaHome)) {
                ProcessRunner.Result baseline = runGradleCommand(repoRoot, command, timeout, baseEnvironment);
                if (baseline.getExitCode() == 0) {
                    log.info("[BUILD] Gradle {} fallback to current runtime JVM succeeded", stage);
                    return baseline;
                }
            }
            return first;
        }
        log.info("[BUILD] Gradle {} detected Java compatibility issue: {}", stage, compatibilityReason.get());

        ProcessRunner.Result last = first;
        for (String javaHome : candidates) {
            if (javaHome.equalsIgnoreCase(selectedJavaHome)) {
                continue;
            }

            log.info("[BUILD] Gradle {} retry with JAVA_HOME={}", stage, javaHome);
            ProcessRunner.Result retry = runGradleCommand(
                    repoRoot,
                    command,
                    timeout,
                    buildToolchainSupport.buildEnvironment(baseEnvironment, javaHome)
            );
            if (retry.getExitCode() == 0) {
                return retry;
            }
            last = retry;
        }

        return last;
    }

    private ProcessRunner.Result runGradleCommand(Path repoRoot,
                                                  List<String> command,
                                                  Duration timeout,
                                                  Map<String, String> environmentOverrides) {
        ProcessRunner.Result first = processRunner.run(repoRoot, command, timeout, environmentOverrides);
        if (first.getExitCode() == 0) {
            return first;
        }

        if (command == null || command.isEmpty()) {
            return first;
        }

        String primaryCommand = command.get(0);
        if (!isGradleWrapperCommand(primaryCommand) || !isWrapperBootstrapFailure(first)) {
            return first;
        }

        // wrapper 부트스트랩 실패(파일 탐색/실행 실패 등)면 시스템 gradle로 1회 폴백
        String fallbackCommand = buildCommandProperties.getGradleCommand();
        if (fallbackCommand == null || fallbackCommand.isBlank() || fallbackCommand.equals(primaryCommand)) {
            return first;
        }

        List<String> fallback = new ArrayList<>(command);
        fallback.set(0, fallbackCommand);

        log.warn("[BUILD] Gradle wrapper command failed to bootstrap. fallbackCommand={}", fallbackCommand);
        ProcessRunner.Result fallbackResult = processRunner.run(repoRoot, fallback, timeout, environmentOverrides);
        if (fallbackResult.getExitCode() == 0) {
            log.info("[BUILD] Gradle fallback command succeeded. command={}", fallbackCommand);
        }
        return fallbackResult;
    }

    private boolean isGradleWrapperCommand(String command) {
        String normalized = command == null ? "" : command.replace("\\", "/").toLowerCase(Locale.ROOT);
        return normalized.endsWith("/gradlew")
                || normalized.endsWith("/gradlew.bat")
                || normalized.equals("gradlew")
                || normalized.equals("gradlew.bat")
                || normalized.equals("./gradlew");
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

    private Map<String, String> resolveGradleBaseEnvironment(Path workspaceRoot) {
        Map<String, String> env = new HashMap<>();
        if (!buildCommandProperties.isIsolatedExecution()) {
            return env;
        }

        // run 폴더 기준이 아니라 공용 base-dir 기준으로 해석해야 runId가 달라도 캐시를 재사용할 수 있다.
        Path basePath = resolveCachePathBase(workspaceRoot);
        Path gradleUserHome = resolveConfiguredCachePath(basePath, buildCommandProperties.getGradleUserHomeDir(), ".gradle-home");
        try {
            Files.createDirectories(gradleUserHome);
            env.put("GRADLE_USER_HOME", gradleUserHome.toString());
            log.info(
                    "[BUILD] Gradle user home resolved. configured={}, basePath={}, resolved={}",
                    buildCommandProperties.getGradleUserHomeDir(),
                    basePath,
                    gradleUserHome
            );
        } catch (IOException e) {
            log.warn("[BUILD] Failed to create GRADLE_USER_HOME directory. path={}", gradleUserHome, e);
        }
        return env;
    }

    /**
     * 캐시 기준 경로를 결정한다.
     * base-dir이 비어있거나 비정상이면 workspaceRoot 기준으로 안전하게 폴백한다.
     */
    private Path resolveCachePathBase(Path workspaceRoot) {
        String configuredBaseDir = workspaceProperties.getBaseDir();
        if (configuredBaseDir == null || configuredBaseDir.isBlank()) {
            return workspaceRoot.toAbsolutePath().normalize();
        }

        try {
            return Path.of(configuredBaseDir).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            log.warn(
                    "[BUILD] Invalid workspace base directory configured. baseDir={}, fallback={}",
                    configuredBaseDir,
                    workspaceRoot,
                    e
            );
            return workspaceRoot.toAbsolutePath().normalize();
        }
    }

    /**
     * 캐시 경로 설정값을 절대경로로 정규화한다.
     * 상대경로는 basePath 하위로 붙여 runId와 무관한 공용 캐시로 사용한다.
     */
    private Path resolveConfiguredCachePath(Path basePath, String configuredPath, String defaultRelativePath) {
        String pathValue = (configuredPath == null || configuredPath.isBlank())
                ? defaultRelativePath
                : configuredPath;
        Path rawPath = Path.of(pathValue);
        if (rawPath.isAbsolute()) {
            return rawPath.toAbsolutePath().normalize();
        }
        return basePath.resolve(rawPath).toAbsolutePath().normalize();
    }
}
