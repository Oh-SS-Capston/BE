package com.example.ossdoc.domain.build.service;

import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import com.example.ossdoc.domain.artifact.service.ArtifactService;
import com.example.ossdoc.domain.build.dto.json.BuildFailure;
import com.example.ossdoc.domain.build.dto.json.BuildManifest;
import com.example.ossdoc.domain.build.dto.json.BuildModuleManifest;
import com.example.ossdoc.domain.build.dto.response.BuildResolveResponse;
import com.example.ossdoc.domain.build.enums.BuildMode;
import com.example.ossdoc.domain.build.enums.BuildToolKind;
import com.example.ossdoc.domain.build.exception.BuildException;
import com.example.ossdoc.domain.build.exception.code.BuildErrorCode;
import com.example.ossdoc.domain.build.support.BuildManifestSelector;
import com.example.ossdoc.domain.build.support.BuildManifestWriter;
import com.example.ossdoc.domain.build.support.BuildToolchainSupport;
import com.example.ossdoc.domain.build.support.GradleBuildSupport;
import com.example.ossdoc.domain.build.support.GradleDumpParser;
import com.example.ossdoc.domain.build.support.GradleInitScriptWriter;
import com.example.ossdoc.domain.build.support.MavenBuildSupport;
import com.example.ossdoc.domain.build.support.MavenJavaVersionResolver;
import com.example.ossdoc.domain.build.support.PomModuleScanner;
import com.example.ossdoc.domain.build.support.ProcessRunner;
import com.example.ossdoc.domain.build.support.RepoRootResolver;
import com.example.ossdoc.domain.build.support.SourceOnlyModuleScanner;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.repository.RepoRunRepository;
import com.example.ossdoc.global.config.BuildCommandProperties;
import com.example.ossdoc.global.properties.WorkspaceProperties;
import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * 역할:
 * 빌드 리졸브 전체 흐름을 오케스트레이션한다.
 *
 * 책임:
 * 1) Run/Workspace/Repo 유효성 검증
 * 2) 빌드 도구 감지 후 Gradle/Maven 실행 분기
 * 3) 빌드 매니페스트 산출물 저장 및 응답 반환
 *
 * 비책임:
 * 세부 실행 정책(Gradle 재시도, dump 파싱, 결과 점수화)은 support 클래스로 위임한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BuildResolveService {

    private final RepoRunRepository repoRunRepository;
    private final ArtifactService artifactService;
    private final RepoRootResolver repoRootResolver;

    private final BuildDetector buildDetector;
    private final BuildManifestWriter buildManifestWriter;
    private final SourceOnlyModuleScanner sourceOnlyModuleScanner;
    private final PomModuleScanner pomModuleScanner;
    private final MavenBuildSupport mavenBuildSupport;
    private final MavenJavaVersionResolver mavenJavaVersionResolver;
    private final BuildCommandProperties buildCommandProperties;
    private final BuildToolchainSupport buildToolchainSupport;
    private final BuildManifestSelector buildManifestSelector;
    private final GradleBuildSupport gradleBuildSupport;
    private final GradleInitScriptWriter gradleInitScriptWriter;
    private final GradleDumpParser gradleDumpParser;
    private final WorkspaceProperties workspaceProperties;

    /**
     * 역할:
     * Build Resolve 유스케이스의 단일 진입점.
     *
     * 책임:
     * 1) 경로/도구 상태 검증
     * 2) Gradle/Maven 단일 혹은 경쟁 실행
     * 3) build_manifest 생성/저장 후 API 응답 생성
     */
    public BuildResolveResponse resolve(String runId) {
        RepoRun run = repoRunRepository.findById(runId)
                .orElseThrow(() -> new BuildException(BuildErrorCode.RUN_NOT_FOUND));

        Path workspaceRoot = Path.of(run.getWorkspaceRoot());
        log.info("[BUILD] Resolve start. runId={}, workspaceRoot={}", runId, workspaceRoot);
        if (!Files.exists(workspaceRoot)) {
            log.debug("workspeaceRoot={}", workspaceRoot);
            throw new BuildException(BuildErrorCode.WORKSPACE_NOT_FOUND);
        }

        Path repoRoot = workspaceRoot.resolve("repo");
        Path actualRepoRoot = repoRootResolver.resolveActualRoot(repoRoot);

        if (!Files.exists(actualRepoRoot)) {
            log.debug("actualRepoRoot={}", actualRepoRoot);
            throw new BuildException(BuildErrorCode.REPO_ROOT_NOT_FOUND);
        }

        Path artifactsDir = workspaceRoot.resolve("artifacts");
        Path tmpDir = workspaceRoot.resolve("tmp");

        BuildDetector.Detected detected = buildDetector.detect(actualRepoRoot);
        log.info(
                "[BUILD] Detection result. repoRoot={}, hasGradle={}, hasMaven={}, gradleWrapper={}, mavenWrapper={}",
                actualRepoRoot,
                detected.hasGradle(),
                detected.hasMaven(),
                detected.gradleWrapperExists(),
                detected.mavenWrapperExists()
        );

        BuildManifest manifest;
        if (!detected.hasAnyBuildTool()) {
            // 빌드 도구 자체가 없으면 즉시 FAILED
            manifest = BuildManifest.builder()
                    .runId(runId)
                    .detectedAt(OffsetDateTime.now())
                    .buildTool(BuildToolKind.NONE)
                    .wrapperUsed(false)
                    .buildMode(BuildMode.FAILED)
                    .failures(List.of(BuildFailure.builder()
                            .code(BuildErrorCode.BUILD_TOOL_NOT_FOUND.getCode())
                            .message(BuildErrorCode.BUILD_TOOL_NOT_FOUND.getMessage())
                            .build()))
                    .build();
        } else if (detected.hasGradle() && detected.hasMaven()) {
            // 혼합 프로젝트(Gradle+Maven)는 둘 다 실행 후 더 좋은 결과를 선택한다.
            log.info("[BUILD] Both Gradle and Maven detected. Running competitive resolve. repoRoot={}", actualRepoRoot);
            BuildManifest gradleManifest = resolveGradle(
                    runId,
                    actualRepoRoot,
                    workspaceRoot,
                    detected.gradleWrapperExists(),
                    tmpDir.resolve("gradle")
            );
            BuildManifest mavenManifest = resolveMaven(
                    runId,
                    actualRepoRoot,
                    workspaceRoot,
                    detected.mavenWrapperExists(),
                    tmpDir.resolve("maven")
            );
            manifest = buildManifestSelector.selectBetter(gradleManifest, mavenManifest);
            log.info(
                    "[BUILD] Competitive resolve selected tool={} mode={} (gradleMode={}, mavenMode={})",
                    manifest.getBuildTool(),
                    manifest.getBuildMode(),
                    gradleManifest.getBuildMode(),
                    mavenManifest.getBuildMode()
            );
        } else if (detected.hasGradle()) {
            log.info("[BUILD] Resolve path selected: GRADLE");
            manifest = resolveGradle(runId, actualRepoRoot, workspaceRoot, detected.gradleWrapperExists(), tmpDir.resolve("gradle"));
        } else {
            log.info("[BUILD] Resolve path selected: MAVEN");
            manifest = resolveMaven(runId, actualRepoRoot, workspaceRoot, detected.mavenWrapperExists(), tmpDir.resolve("maven"));
        }

        JsonNode manifestJson = buildManifestWriter.toJson(manifest);
        buildManifestWriter.write(artifactsDir, manifestJson);
        Artifact savedManifest = artifactService.saveJsonArtifact(run, ArtifactKind.BUILD_MANIFEST, "0.1",
                "build_manifest.json", manifestJson);

        log.info("[BUILD] Resolve end. runId={}, mode={}, manifestPath={}", runId, manifest.getBuildMode(), savedManifest.getPath());
        return new BuildResolveResponse(runId, manifest.getBuildMode(), savedManifest.getPath());
    }

    /**
     * 역할:
     * Gradle 기반 빌드/리졸브를 수행한다.
     *
     * 책임:
     * 1) ossdocDump 실행 후 모듈 메타데이터 수집
     * 2) classes 컴파일 결과를 반영해 BuildMode 결정
     * 3) 실패 시 SOURCE_ONLY 폴백 정보를 failures에 기록
     */
    private BuildManifest resolveGradle(String runId, Path repoRoot, Path workspaceRoot, boolean wrapperUsed, Path tmpDir) {
        List<BuildModuleManifest> modules = new ArrayList<>();
        List<BuildFailure> failures = new ArrayList<>();
        log.info("[BUILD] Gradle resolve start. runId={}, repoRoot={}, wrapperUsed={}", runId, repoRoot, wrapperUsed);

        Path init = gradleInitScriptWriter.write(tmpDir);

        // Gradle 명령을 가변 리스트로 구성해 데몬 옵션을 설정값으로 제어한다.
        List<String> dumpCmd = new ArrayList<>();
        dumpCmd.add(gradleBuildSupport.selectGradleCmd(repoRoot));
        dumpCmd.add("-I");
        dumpCmd.add(init.toString());
        dumpCmd.add("ossdocDump");
        dumpCmd.add("-q");
        appendGradleDaemonOption(dumpCmd);

        ProcessRunner.Result dump = gradleBuildSupport.runWithJavaFallback(
                repoRoot,
                workspaceRoot,
                dumpCmd,
                Duration.ofMinutes(20),
                "dump"
        );
        log.info("[BUILD] Gradle dump finished. exitCode={}", dump.getExitCode());
        if (dump.getExitCode() == 0) {
            modules.addAll(gradleDumpParser.parse(repoRoot, dump.getOutput(), failures));

            if (modules.isEmpty()) {
                failures.add(BuildFailure.builder()
                        .code("SOURCE_ONLY")
                        .message("Gradle dump succeeded but no modules were extracted, so source-only fallback was used")
                        .build());

                modules.addAll(sourceOnlyModuleScanner.scan(repoRoot));
            }

        } else {
            failures.add(BuildFailure.builder()
                    .code(BuildErrorCode.GRADLE_DUMP_FAILED.getCode())
                    .message(BuildErrorCode.GRADLE_DUMP_FAILED.getMessage())
                    .logHint(hint(dump))
                    .build());

            modules.addAll(sourceOnlyModuleScanner.scan(repoRoot));
        }

        // compile 단계도 동일하게 데몬 옵션을 설정 기반으로 붙인다.
        List<String> compileCmd = new ArrayList<>();
        compileCmd.add(gradleBuildSupport.selectGradleCmd(repoRoot));
        compileCmd.add("classes");
        compileCmd.add("-x");
        compileCmd.add("test");
        compileCmd.add("-x");
        compileCmd.add("check");
        appendGradleDaemonOption(compileCmd);

        ProcessRunner.Result compile = gradleBuildSupport.runWithJavaFallback(
                repoRoot,
                workspaceRoot,
                compileCmd,
                Duration.ofMinutes(40),
                "compile"
        );
        log.info("[BUILD] Gradle compile finished. exitCode={}", compile.getExitCode());

        BuildMode mode = decideBuildMode(modules, compile);
        if (compile.getExitCode() != 0 && mode != BuildMode.FAILED) {
            failures.add(BuildFailure.builder()
                    .code(BuildErrorCode.GRADLE_COMPILE_FAILED.getCode())
                    .message(BuildErrorCode.GRADLE_COMPILE_FAILED.getMessage())
                    .logHint(hint(compile))
                    .build());
        }

        return BuildManifest.builder()
                .runId(runId)
                .detectedAt(OffsetDateTime.now())
                .buildTool(BuildToolKind.GRADLE)
                .wrapperUsed(wrapperUsed)
                .buildMode(mode)
                .modules(modules)
                .failures(failures)
                .build();
    }

    /**
     * 역할:
     * Maven 실행 불가/예외 상황에서 source-only 결과를 생성한다.
     *
     * 책임:
     * 1) 소스 루트 스캔 결과만으로 최소 매니페스트 구성
     * 2) 실패 원인을 BuildFailure로 명시
     */
    private BuildManifest resolveMavenSourceOnly(String runId, Path repoRoot, boolean wrapperUsed, String reason, String logHint) {
        log.warn("[BUILD] Maven source-only fallback. runId={}, repoRoot={}, reason={}", runId, repoRoot, reason);
        List<BuildModuleManifest> modules = sourceOnlyModuleScanner.scan(repoRoot);

        List<BuildFailure> failures = new ArrayList<>();
        failures.add(BuildFailure.builder()
                .code("SOURCE_ONLY")
                .message(reason)
                .logHint(logHint)
                .build());

        BuildMode mode = modules.isEmpty() ? BuildMode.FAILED : BuildMode.SOURCE_ONLY;

        return BuildManifest.builder()
                .runId(runId)
                .detectedAt(OffsetDateTime.now())
                .buildTool(BuildToolKind.MAVEN)
                .wrapperUsed(wrapperUsed)
                .buildMode(mode)
                .modules(modules)
                .failures(failures)
                .build();
    }

    /**
     * 역할:
     * Maven 실행 시 JAVA_HOME 후보를 순차 적용해 재시도한다.
     *
     * 책임:
     * 1) 1순위 JDK로 실행
     * 2) 호환성 에러 감지 시 후보 JDK 순차 재시도
     * 3) 필요 시 현재 런타임 JVM으로 baseline 폴백
     */
    private ProcessRunner.Result runMavenWithJavaFallback(Path mavenLocalRepoPath,
                                                          MavenJavaSelection selection,
                                                          String stage,
                                                          Function<Map<String, String>, ProcessRunner.Result> runner) {
        List<String> candidates = selection.javaHomes();

        ProcessRunner.Result first;
        String selectedJavaHome = null;
        if (candidates.isEmpty()) {
            first = runner.apply(Map.of());
        } else {
            selectedJavaHome = candidates.get(0);
            first = runner.apply(buildToolchainSupport.buildEnvironment(Map.of(), selectedJavaHome));
            log.info(
                    "[BUILD] Maven {} initial JAVA_HOME={} (requiredJava={}, repoLocal={})",
                    stage,
                    selectedJavaHome,
                    selection.requiredJavaMajor().orElse(null),
                    mavenLocalRepoPath
            );
        }

        if (first.getExitCode() == 0) {
            return first;
        }

        Optional<String> compatibilityReason = buildToolchainSupport.detectJavaCompatibilityReason(BuildToolKind.MAVEN, first);
        if (compatibilityReason.isEmpty()) {
            if (selectedJavaHome != null && !buildToolchainSupport.isCurrentRuntimeJavaHome(selectedJavaHome)) {
                ProcessRunner.Result baseline = runner.apply(Map.of());
                if (baseline.getExitCode() == 0) {
                    log.info("[BUILD] Maven {} fallback to current runtime JVM succeeded", stage);
                    return baseline;
                }
            }
            return first;
        }
        log.info("[BUILD] Maven {} detected Java compatibility issue: {}", stage, compatibilityReason.get());

        ProcessRunner.Result last = first;
        for (String javaHome : candidates) {
            if (javaHome.equalsIgnoreCase(selectedJavaHome)) {
                continue;
            }
            log.info("[BUILD] Maven {} retry with JAVA_HOME={}", stage, javaHome);
            ProcessRunner.Result retry = runner.apply(buildToolchainSupport.buildEnvironment(Map.of(), javaHome));
            if (retry.getExitCode() == 0) {
                return retry;
            }
            last = retry;
        }

        return last;
    }

    private MavenJavaSelection resolveMavenJavaSelection(Path repoRoot) {
        Optional<Integer> requiredJavaMajor = mavenJavaVersionResolver.resolveRequiredJavaMajor(repoRoot);
        List<String> javaHomes = buildToolchainSupport.resolveJavaHomeCandidatesForMaven(requiredJavaMajor);
        return new MavenJavaSelection(requiredJavaMajor, javaHomes);
    }

    /**
     * 역할:
     * 격리 실행 모드에서 Run 전용 Maven local repository 경로를 준비한다.
     *
     * 책임:
     * 1) 디렉터리 생성 보장
     * 2) 생성 실패 시 경고 로그 후 null 반환
     */
    private Path resolveMavenLocalRepoPath(Path workspaceRoot) {
        if (!buildCommandProperties.isIsolatedExecution()) {
            return null;
        }

        // run 폴더 기준이 아니라 공용 base-dir 기준으로 해석해야 runId가 달라도 캐시를 재사용할 수 있다.
        Path basePath = resolveCachePathBase(workspaceRoot);
        Path mavenLocalRepo = resolveConfiguredCachePath(basePath, buildCommandProperties.getMavenLocalRepoDir(), ".m2/repository");
        try {
            Files.createDirectories(mavenLocalRepo);
            log.info(
                    "[BUILD] Maven local repository resolved. configured={}, basePath={}, resolved={}",
                    buildCommandProperties.getMavenLocalRepoDir(),
                    basePath,
                    mavenLocalRepo
            );
            return mavenLocalRepo;
        } catch (IOException e) {
            log.warn("[BUILD] Failed to create Maven local repository path. path={}", mavenLocalRepo, e);
            return null;
        }
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

    private BuildMode decideBuildMode(List<BuildModuleManifest> modules, ProcessRunner.Result compile) {
        if (modules.isEmpty()) return BuildMode.FAILED;

        boolean anyClassesDirs = modules.stream().anyMatch(m -> m.getClassesDirs() != null && !m.getClassesDirs().isEmpty());

        if (compile.getExitCode() == 0 && anyClassesDirs) return BuildMode.FULL;
        if (compile.getExitCode() == 0) return BuildMode.COMPILE_ONLY;
        return BuildMode.SOURCE_ONLY;
    }

    private String hint(ProcessRunner.Result r) {
        String base = (r.getOutput() != null && !r.getOutput().isBlank())
                ? r.getOutput()
                : (r.getError() == null ? "" : r.getError());
        return base.substring(0, Math.min(600, base.length()));
    }

    /**
     * Gradle 데몬 사용 정책을 명령에 반영한다.
     * 기본값(false)은 데몬 재사용으로 반복 실행 속도를 높이고,
     * 필요 시 gradle-no-daemon=true로 기존 동작을 강제할 수 있다.
     */
    private void appendGradleDaemonOption(List<String> command) {
        if (buildCommandProperties.isGradleNoDaemon()) {
            command.add("--no-daemon");
        }
    }

    /**
     * 역할:
     * Maven 기반 빌드/리졸브를 수행한다.
     *
     * 책임:
     * 1) 모듈 스캔 + classpath 생성
     * 2) compile 수행 후 BuildMode 결정
     * 3) 실패 시 SOURCE_ONLY로 안전 폴백
     */
    private BuildManifest resolveMaven(String runId, Path repoRoot, Path workspaceRoot, boolean wrapperUsed, Path tmpDir) {
        List<BuildFailure> failures = new ArrayList<>();
        List<BuildModuleManifest> modules = new ArrayList<>();
        log.info("[BUILD] Maven resolve start. runId={}, repoRoot={}, wrapperUsed={}", runId, repoRoot, wrapperUsed);

        try {
            Files.createDirectories(tmpDir);

            List<Path> moduleRoots = pomModuleScanner.scanModuleRoots(repoRoot);

            if (moduleRoots.isEmpty()) {
                return resolveMavenSourceOnly(
                        runId,
                        repoRoot,
                        wrapperUsed,
                        "No Maven modules were found, so source-only fallback was used",
                        null
                );
            }

            Path classpathFile = tmpDir.resolve("maven-classpath.txt");
            Path mavenLocalRepoPath = resolveMavenLocalRepoPath(workspaceRoot);
            MavenJavaSelection mavenJavaSelection = resolveMavenJavaSelection(repoRoot);
            log.info(
                    "[BUILD] Maven Java selection. requiredMajor={}, candidates={}",
                    mavenJavaSelection.requiredJavaMajor().orElse(null),
                    mavenJavaSelection.javaHomes()
            );
            ProcessRunner.Result cpResult = runMavenWithJavaFallback(
                    mavenLocalRepoPath,
                    mavenJavaSelection,
                    "classpath",
                    env -> mavenBuildSupport.buildClasspath(repoRoot, classpathFile, mavenLocalRepoPath, env)
            );
            log.info("[BUILD] Maven classpath finished. exitCode={}", cpResult.getExitCode());

            List<String> classpath = List.of();
            if (cpResult.getExitCode() == 0) {
                classpath = mavenBuildSupport.readClasspathFile(repoRoot, classpathFile);
            } else {
                failures.add(BuildFailure.builder()
                        .code("MAVEN_CLASSPATH_FAILED")
                        .message("Failed to build Maven classpath, fallback continues")
                        .logHint(hint(cpResult))
                        .build());
            }

            ProcessRunner.Result compileResult = runMavenWithJavaFallback(
                    mavenLocalRepoPath,
                    mavenJavaSelection,
                    "compile",
                    env -> mavenBuildSupport.compile(repoRoot, mavenLocalRepoPath, env)
            );
            log.info("[BUILD] Maven compile finished. exitCode={}", compileResult.getExitCode());

            for (Path moduleRoot : moduleRoots) {
                modules.add(mavenBuildSupport.toModuleManifest(repoRoot, moduleRoot, classpath));
            }

            if (modules.isEmpty()) {
                return resolveMavenSourceOnly(
                        runId,
                        repoRoot,
                        wrapperUsed,
                        "No Maven module manifests were created, so source-only fallback was used",
                        null
                );
            }

            BuildMode mode = decideBuildMode(modules, compileResult);

            if (compileResult.getExitCode() != 0) {
                failures.add(BuildFailure.builder()
                        .code("MAVEN_COMPILE_FAILED")
                        .message("Maven compile failed, source-only fallback was used")
                        .logHint(hint(compileResult))
                        .build());

                return BuildManifest.builder()
                        .runId(runId)
                        .detectedAt(OffsetDateTime.now())
                        .buildTool(BuildToolKind.MAVEN)
                        .wrapperUsed(wrapperUsed)
                        .buildMode(BuildMode.SOURCE_ONLY)
                        .modules(modules)
                        .failures(failures)
                        .build();
            }

            return BuildManifest.builder()
                    .runId(runId)
                    .detectedAt(OffsetDateTime.now())
                    .buildTool(BuildToolKind.MAVEN)
                    .wrapperUsed(wrapperUsed)
                    .buildMode(mode)
                    .modules(modules)
                    .failures(failures)
                    .build();

        } catch (Exception e) {
            log.warn("[BUILD] Maven resolve exception. runId={}, repoRoot={}", runId, repoRoot, e);
            return resolveMavenSourceOnly(
                    runId,
                    repoRoot,
                    wrapperUsed,
                    "Exception occurred during Maven resolve, so source-only fallback was used",
                    e.getMessage()
            );
        }
    }

    private record MavenJavaSelection(Optional<Integer> requiredJavaMajor, List<String> javaHomes) {
    }
}
