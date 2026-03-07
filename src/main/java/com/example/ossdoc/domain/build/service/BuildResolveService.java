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
import com.example.ossdoc.domain.build.support.*;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.repository.RepoRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.*;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class BuildResolveService {

    private final RepoRunRepository repoRunRepository;
    private final ArtifactService artifactService;
    private final RepoRootResolver repoRootResolver;

    private final BuildDetector buildDetector;
    private final ProcessRunner processRunner;
    private final GradleInitScriptWriter gradleInitScriptWriter;
    private final BuildManifestWriter buildManifestWriter;
    private final SourceOnlyModuleScanner sourceOnlyModuleScanner;
    private final PomModuleScanner pomModuleScanner;
    private final MavenBuildSupport mavenBuildSupport;

    private final ObjectMapper objectMapper;

    public BuildResolveResponse resolve(String runId) {
        RepoRun run = repoRunRepository.findById(runId)
                .orElseThrow(() -> new BuildException(BuildErrorCode.RUN_NOT_FOUND));

        Path workspaceRoot = Path.of(run.getWorkspaceRoot());
        if (!Files.exists(workspaceRoot)) {
            log.debug("workspeaceRoot={}", workspaceRoot.toString());
            throw new BuildException(BuildErrorCode.WORKSPACE_NOT_FOUND);
        }

        // 너희 run에서 repo를 어디에 풀어두는지에 맞춰 여기만 맞추면 됨
        Path repoRoot = workspaceRoot.resolve("repo");
        Path actualRepoRoot = repoRootResolver.resolveActualRoot(repoRoot);

        if (!Files.exists(actualRepoRoot)) {
            log.debug("actualRepoRoot={}", actualRepoRoot.toString());
            throw new BuildException(BuildErrorCode.REPO_ROOT_NOT_FOUND);
        }

        Path artifactsDir = workspaceRoot.resolve("artifacts");
        Path tmpDir = workspaceRoot.resolve("tmp");

        BuildDetector.Detected detected = buildDetector.detect(actualRepoRoot);

        BuildManifest manifest = switch (detected.tool()) {
            case GRADLE -> resolveGradle(runId, actualRepoRoot, detected.wrapperExists(), tmpDir);
            case MAVEN -> resolveMaven(runId, actualRepoRoot, detected.wrapperExists(), tmpDir);
            case NONE -> BuildManifest.builder()
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
        };

        Path buildManifestPath = buildManifestWriter.write(artifactsDir, manifest);

        JsonNode meta = objectMapper.createObjectNode()
                .put("buildTool", manifest.getBuildTool().name())
                .put("buildMode", manifest.getBuildMode().name())
                .put("moduleCount", manifest.getModules().size())
                .put("failureCount", manifest.getFailures().size())
                .put("hasClassesDirs",
                        manifest.getModules().stream()
                                .anyMatch(m -> m.getClassesDirs() != null && !m.getClassesDirs().isEmpty()));

        // Artifact 등록(너희 artifact 도메인에 이미 있으니 재사용)
        artifactService.saveJsonArtifact(run, ArtifactKind.BUILD_MANIFEST,"0.1", buildManifestPath.toString(), meta);

        return new BuildResolveResponse(runId, manifest.getBuildMode(), buildManifestPath.toString());
    }

    private BuildManifest resolveGradle(String runId, Path repoRoot, boolean wrapperUsed, Path tmpDir) {
        List<BuildModuleManifest> modules = new ArrayList<>();
        List<BuildFailure> failures = new ArrayList<>();

        // 1) 덤프(모듈/소스루트/classesDirs/classpath) — 빌드가 깨져도 성공하는 경우가 많아서 핵심
        Path init = gradleInitScriptWriter.write(tmpDir);

        List<String> dumpCmd = List.of(
                selectGradleCmd(repoRoot),
                "-I", init.toString(),
                "ossdocDump",
                "-q",
                "--no-daemon"
        );

        ProcessRunner.Result dump = processRunner.run(repoRoot, dumpCmd, Duration.ofMinutes(10));
        if (dump.getExitCode() == 0) {
            modules.addAll(parseGradleDump(dump.getOutput(), failures));

            // dump는 성공했는데 실제 모듈 추출 결과가 없으면 SOURCE_ONLY 폴백
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

            // dump 실패해도 AST가 돌 수 있게 source-only 폴백
            modules.addAll(sourceOnlyModuleScanner.scan(repoRoot));
        }

        // 2) 컴파일 시도(성공률 우선: 테스트/체크 스킵)
        List<String> compileCmd = List.of(
                selectGradleCmd(repoRoot),
                "classes",
                "-x", "test",
                "-x", "check",
                "--no-daemon"
        );

        ProcessRunner.Result compile = processRunner.run(repoRoot, compileCmd, Duration.ofMinutes(20));

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

    private String selectGradleCmd(Path repoRoot) {
        // Windows 개발 환경이면 gradlew.bat 우선
        if (Files.exists(repoRoot.resolve("gradlew.bat"))) return "gradlew.bat";
        if (Files.exists(repoRoot.resolve("gradlew"))) return "./gradlew";
        return "gradle";
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

    private List<BuildModuleManifest> parseGradleDump(String output, List<BuildFailure> failures) {
        Pattern p = Pattern.compile("^OSS_DOC_DUMP=(\\{.*\\})$", Pattern.MULTILINE);
        Matcher m = p.matcher(output);

        List<BuildModuleManifest> modules = new ArrayList<>();
        while (m.find()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = objectMapper.readValue(m.group(1), Map.class);

                List<String> sourceRoots = (List<String>) map.getOrDefault("sourceRoots", List.of());
                List<String> testRoots = (List<String>) map.getOrDefault("testRoots", List.of());
                List<String> resourceRoots = (List<String>) map.getOrDefault("resourceRoots", List.of());
                List<String> classesDirs = (List<String>) map.getOrDefault("classesDirs", List.of());
                List<String> compileClasspath = (List<String>) map.getOrDefault("compileClasspath", List.of());
                List<String> runtimeClasspath = (List<String>) map.getOrDefault("runtimeClasspath", List.of());

                /**
                 * OK → classesDirs까지 있음 → ASM 기대 가능
                 * PARTIAL → sourceRoots는 있음 → AST 가능
                 * FAILED → 실질 정보 없음
                 */
                String status;
                if (!classesDirs.isEmpty()) {
                    status = "OK";
                } else if (!sourceRoots.isEmpty() || !testRoots.isEmpty() || !resourceRoots.isEmpty()) {
                    status = "PARTIAL";
                } else {
                    status = "FAILED";
                }

                modules.add(BuildModuleManifest.builder()
                        .moduleId((String) map.getOrDefault("projectPath", ""))
                        .name((String) map.getOrDefault("name", ""))
                        .sourceRoots(sourceRoots)
                        .testRoots(testRoots)
                        .resourceRoots(resourceRoots)
                        .classesDirs(classesDirs)
                        .compileClasspath(compileClasspath)
                        .runtimeClasspath(runtimeClasspath)
                        .status(status)
                        .build());

            } catch (Exception e) {
                failures.add(BuildFailure.builder()
                        .code(BuildErrorCode.GRADLE_DUMP_FAILED.getCode())
                        .message("Failed to parse OSS_DOC_DUMP - " + e.getMessage())
                        .build());
            }
        }
        return modules;
    }

    private BuildManifest resolveMaven(String runId, Path repoRoot, boolean wrapperUsed, Path tmpDir) {
        List<BuildFailure> failures = new ArrayList<>();
        List<BuildModuleManifest> modules = new ArrayList<>();

        try {
            // 1) 모듈 루트 파악
            List<Path> moduleRoots = pomModuleScanner.scanModuleRoots(repoRoot);

            // 모듈 루트조차 못 찾으면 SOURCE_ONLY 폴백
            if (moduleRoots.isEmpty()) {
                return resolveMavenSourceOnly(
                        runId,
                        repoRoot,
                        wrapperUsed,
                        "No Maven modules were found, so source-only fallback was used",
                        null
                );
            }

            // 2) classpath 확보 시도
            Path classpathFile = tmpDir.resolve("maven-classpath.txt");
            ProcessRunner.Result cpResult = mavenBuildSupport.buildClasspath(repoRoot, classpathFile);

            List<String> classpath = List.of();
            if (cpResult.getExitCode() == 0) {
                classpath = mavenBuildSupport.readClasspathFile(classpathFile);
            } else {
                failures.add(BuildFailure.builder()
                        .code("MAVEN_CLASSPATH_FAILED")
                        .message("Failed to build Maven classpath, fallback continues")
                        .logHint(hint(cpResult))
                        .build());
            }

            // 3) 컴파일 시도
            ProcessRunner.Result compileResult = mavenBuildSupport.compile(repoRoot);

            // 4) 모듈별 manifest 생성
            for (Path moduleRoot : moduleRoots) {
                modules.add(mavenBuildSupport.toModuleManifest(repoRoot, moduleRoot, classpath));
            }

            // 모듈 manifest 자체가 비면 SOURCE_ONLY 폴백
            if (modules.isEmpty()) {
                return resolveMavenSourceOnly(
                        runId,
                        repoRoot,
                        wrapperUsed,
                        "No Maven module manifests were created, so source-only fallback was used",
                        null
                );
            }

            // 5) buildMode 결정
            BuildMode mode = decideBuildMode(modules, compileResult);

            // 6) compile 실패 시:
            //    이미 만든 modules가 있으면 그걸 유지하고 SOURCE_ONLY로 반환
            //    (scanner로 다시 덮어쓰지 않음)
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

            // 7) 성공 케이스
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
}
