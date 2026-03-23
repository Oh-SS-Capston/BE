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
import com.example.ossdoc.domain.build.support.BuildPathNormalizer;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final BuildPathNormalizer buildPathNormalizer;
    private final BuildCommandProperties buildCommandProperties;
    private final BuildToolchainSupport buildToolchainSupport;
    private final BuildManifestSelector buildManifestSelector;
    private final GradleBuildSupport gradleBuildSupport;
    private final GradleInitScriptWriter gradleInitScriptWriter;
    private final GradleDumpParser gradleDumpParser;

    private final ObjectMapper objectMapper;

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
            manifest = resolveGradle(runId, actualRepoRoot, workspaceRoot, detected.gradleWrapperExists(), tmpDir.resolve("gradle"));
        } else {
            manifest = resolveMaven(runId, actualRepoRoot, workspaceRoot, detected.mavenWrapperExists(), tmpDir.resolve("maven"));
        }

        Path buildManifestPath = buildManifestWriter.write(artifactsDir, manifest);
        JsonNode manifestJson = objectMapper.valueToTree(manifest);

        artifactService.saveJsonArtifact(run, ArtifactKind.BUILD_MANIFEST, "0.1",
                "build_manifest.json", manifestJson);

        return new BuildResolveResponse(runId, manifest.getBuildMode(), buildPathNormalizer.normalize(buildManifestPath));
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

        Path init = gradleInitScriptWriter.write(tmpDir);

        List<String> dumpCmd = List.of(
                gradleBuildSupport.selectGradleCmd(repoRoot),
                "-I", init.toString(),
                "ossdocDump",
                "-q",
                "--no-daemon"
        );

        ProcessRunner.Result dump = gradleBuildSupport.runWithJavaFallback(
                repoRoot,
                workspaceRoot,
                dumpCmd,
                Duration.ofMinutes(10),
                "dump"
        );
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

        List<String> compileCmd = List.of(
                gradleBuildSupport.selectGradleCmd(repoRoot),
                "classes",
                "-x", "test",
                "-x", "check",
                "--no-daemon"
        );

        ProcessRunner.Result compile = gradleBuildSupport.runWithJavaFallback(
                repoRoot,
                workspaceRoot,
                compileCmd,
                Duration.ofMinutes(20),
                "compile"
        );

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
        Path mavenLocalRepo = workspaceRoot.resolve(buildCommandProperties.getMavenLocalRepoDir())
                .toAbsolutePath()
                .normalize();
        try {
            Files.createDirectories(mavenLocalRepo);
            return mavenLocalRepo;
        } catch (IOException e) {
            log.warn("[BUILD] Failed to create Maven local repository path. path={}", mavenLocalRepo, e);
            return null;
        }
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
            ProcessRunner.Result cpResult = runMavenWithJavaFallback(
                    mavenLocalRepoPath,
                    mavenJavaSelection,
                    "classpath",
                    env -> mavenBuildSupport.buildClasspath(repoRoot, classpathFile, mavenLocalRepoPath, env)
            );

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
