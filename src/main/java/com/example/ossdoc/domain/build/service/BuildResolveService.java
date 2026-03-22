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
import com.example.ossdoc.global.config.BuildCommandProperties;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.repository.RepoRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Function;

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
    private final MavenJavaVersionResolver mavenJavaVersionResolver;
    private final BuildPathNormalizer buildPathNormalizer;
    private final BuildCommandProperties buildCommandProperties;

    private final ObjectMapper objectMapper;

    public BuildResolveResponse resolve(String runId) {
        RepoRun run = repoRunRepository.findById(runId)
                .orElseThrow(() -> new BuildException(BuildErrorCode.RUN_NOT_FOUND));

        Path workspaceRoot = Path.of(run.getWorkspaceRoot());
        if (!Files.exists(workspaceRoot)) {
            log.debug("workspeaceRoot={}", workspaceRoot.toString());
            throw new BuildException(BuildErrorCode.WORKSPACE_NOT_FOUND);
        }

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
            case GRADLE -> resolveGradle(runId, actualRepoRoot, workspaceRoot, detected.wrapperExists(), tmpDir);
            case MAVEN -> resolveMaven(runId, actualRepoRoot, workspaceRoot, detected.wrapperExists(), tmpDir);
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

        JsonNode manifestJson = objectMapper.valueToTree(manifest);

        artifactService.saveJsonArtifact(run, ArtifactKind.BUILD_MANIFEST, "0.1",
                "build_manifest.json", manifestJson);

        return new BuildResolveResponse(runId, manifest.getBuildMode(), buildPathNormalizer.normalize(buildManifestPath));
    }

    private BuildManifest resolveGradle(String runId, Path repoRoot, Path workspaceRoot, boolean wrapperUsed, Path tmpDir) {
        List<BuildModuleManifest> modules = new ArrayList<>();
        List<BuildFailure> failures = new ArrayList<>();

        Path init = gradleInitScriptWriter.write(tmpDir);

        List<String> dumpCmd = List.of(
                selectGradleCmd(repoRoot),
                "-I", init.toString(),
                "ossdocDump",
                "-q",
                "--no-daemon"
        );

        ProcessRunner.Result dump = runGradleWithJavaFallback(repoRoot, workspaceRoot, dumpCmd, Duration.ofMinutes(10), "dump");
        if (dump.getExitCode() == 0) {
            modules.addAll(parseGradleDump(repoRoot, dump.getOutput(), failures));

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
                selectGradleCmd(repoRoot),
                "classes",
                "-x", "test",
                "-x", "check",
                "--no-daemon"
        );

        ProcessRunner.Result compile = runGradleWithJavaFallback(repoRoot, workspaceRoot, compileCmd, Duration.ofMinutes(20), "compile");

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
        // Windows   gradlew.bat 
        if (Files.exists(repoRoot.resolve("gradlew.bat"))) return "gradlew.bat";
        if (Files.exists(repoRoot.resolve("gradlew"))) return "./gradlew";
        return buildCommandProperties.getGradleCommand();
    }

    private ProcessRunner.Result runGradleWithJavaFallback(Path repoRoot,
                                                           Path workspaceRoot,
                                                           List<String> command,
                                                           Duration timeout,
                                                           String stage) {
        Map<String, String> baseEnvironment = resolveGradleBaseEnvironment(workspaceRoot);
        List<String> candidates = resolveJavaHomeCandidates(repoRoot);

        ProcessRunner.Result first;
        String selectedJavaHome = null;
        if (candidates.isEmpty()) {
            first = processRunner.run(repoRoot, command, timeout, baseEnvironment);
        } else {
            selectedJavaHome = candidates.get(0);
            first = processRunner.run(repoRoot, command, timeout, buildEnvironment(baseEnvironment, selectedJavaHome));
            log.info("[BUILD] Gradle {} initial JAVA_HOME={} (version-aware selection)", stage, selectedJavaHome);
        }

        if (first.getExitCode() == 0) {
            return first;
        }

        Optional<String> compatibilityReason = detectJavaCompatibilityReason(BuildToolKind.GRADLE, first);
        if (compatibilityReason.isEmpty()) {
            if (selectedJavaHome != null && !isCurrentRuntimeJavaHome(selectedJavaHome)) {
                ProcessRunner.Result baseline = processRunner.run(repoRoot, command, timeout, baseEnvironment);
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
            ProcessRunner.Result retry = processRunner.run(
                    repoRoot,
                    command,
                    timeout,
                    buildEnvironment(baseEnvironment, javaHome)
            );
            if (retry.getExitCode() == 0) {
                return retry;
            }
            last = retry;
        }

        return last;
    }

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
            first = runner.apply(buildEnvironment(Map.of(), selectedJavaHome));
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

        Optional<String> compatibilityReason = detectJavaCompatibilityReason(BuildToolKind.MAVEN, first);
        if (compatibilityReason.isEmpty()) {
            if (selectedJavaHome != null && !isCurrentRuntimeJavaHome(selectedJavaHome)) {
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
            ProcessRunner.Result retry = runner.apply(buildEnvironment(Map.of(), javaHome));
            if (retry.getExitCode() == 0) {
                return retry;
            }
            last = retry;
        }

        return last;
    }

    private MavenJavaSelection resolveMavenJavaSelection(Path repoRoot) {
        Optional<Integer> requiredJavaMajor = mavenJavaVersionResolver.resolveRequiredJavaMajor(repoRoot);
        List<String> javaHomes = resolveJavaHomeCandidatesForMaven(requiredJavaMajor);
        return new MavenJavaSelection(requiredJavaMajor, javaHomes);
    }

    private Optional<String> detectJavaCompatibilityReason(BuildToolKind buildToolKind, ProcessRunner.Result result) {
        String text = ((result.getOutput() == null ? "" : result.getOutput()) + "\n"
                + (result.getError() == null ? "" : result.getError())).toLowerCase(Locale.ROOT);

        List<String> commonSignals = List.of(
                "unsupported class file major version",
                "unsupported major.minor version",
                "has been compiled by a more recent version",
                "could not determine java version"
        );
        for (String signal : commonSignals) {
            if (text.contains(signal)) {
                return Optional.of(signal);
            }
        }

        if (buildToolKind == BuildToolKind.GRADLE) {
            List<String> gradleSignals = List.of(
                    "this version of gradle supports java",
                    "minimum supported gradle version"
            );
            for (String signal : gradleSignals) {
                if (text.contains(signal)) {
                    return Optional.of(signal);
                }
            }
            return Optional.empty();
        }

        if (buildToolKind == BuildToolKind.MAVEN) {
            List<String> mavenSignals = List.of(
                    "invalid target release",
                    "invalid source release",
                    "release version",
                    "source option",
                    "target option",
                    "no toolchain found for type jdk",
                    "cannot find matching toolchain definitions for the following toolchain types",
                    "error: source release",
                    "error: target release"
            );
            for (String signal : mavenSignals) {
                if (!text.contains(signal)) {
                    continue;
                }

                if ("release version".equals(signal) && !text.contains("not supported")) {
                    continue;
                }
                if (("source option".equals(signal) || "target option".equals(signal))
                        && !text.contains("is no longer supported")) {
                    continue;
                }
                return Optional.of(signal);
            }
        }

        return Optional.empty();
    }

    private boolean isCurrentRuntimeJavaHome(String javaHome) {
        String normalized = normalizePathString(javaHome);
        if (normalized == null) {
            return false;
        }
        String currentJavaHome = normalizePathString(System.getenv("JAVA_HOME"));
        String currentRuntimeHome = normalizePathString(System.getProperty("java.home"));
        return normalized.equalsIgnoreCase(currentJavaHome) || normalized.equalsIgnoreCase(currentRuntimeHome);
    }

    private List<String> resolveJavaHomeCandidates(Path repoRoot) {
        List<JavaHomeCandidate> candidates = loadConfiguredJavaHomeCandidates();
        if (candidates.isEmpty()) {
            return List.of();
        }

        Optional<GradleVersion> gradleVersion = parseGradleVersionFromWrapper(repoRoot);
        if (gradleVersion.isPresent()) {
            GradleVersion version = gradleVersion.get();
            int preferredJava = preferredJavaMajor(version);

            candidates.sort((left, right) -> {
                int tierCompare = Integer.compare(
                        compatibilityTier(version, right.major()),
                        compatibilityTier(version, left.major())
                );
                if (tierCompare != 0) {
                    return tierCompare;
                }

                int leftDistance = Math.abs(left.major() - preferredJava);
                int rightDistance = Math.abs(right.major() - preferredJava);
                int distanceCompare = Integer.compare(leftDistance, rightDistance);
                if (distanceCompare != 0) {
                    return distanceCompare;
                }
                return Integer.compare(right.major(), left.major());
            });
        }
        return toJavaHomePaths(candidates);
    }

    private List<String> resolveJavaHomeCandidatesForMaven(Optional<Integer> requiredJavaMajor) {
        List<JavaHomeCandidate> candidates = loadConfiguredJavaHomeCandidates();
        if (candidates.isEmpty()) {
            return List.of();
        }

        if (requiredJavaMajor.isPresent()) {
            int requiredMajor = requiredJavaMajor.get();
            candidates.sort((left, right) -> {
                boolean leftAdequate = left.major() >= requiredMajor;
                boolean rightAdequate = right.major() >= requiredMajor;

                if (leftAdequate != rightAdequate) {
                    return Boolean.compare(rightAdequate, leftAdequate);
                }

                if (leftAdequate) {
                    int leftDistance = Math.abs(left.major() - requiredMajor);
                    int rightDistance = Math.abs(right.major() - requiredMajor);
                    int distanceCompare = Integer.compare(leftDistance, rightDistance);
                    if (distanceCompare != 0) {
                        return distanceCompare;
                    }
                    return Integer.compare(left.major(), right.major());
                }

                return Integer.compare(right.major(), left.major());
            });
        }

        return toJavaHomePaths(candidates);
    }

    private List<JavaHomeCandidate> loadConfiguredJavaHomeCandidates() {
        List<String> configured = buildCommandProperties.getJavaHomes();
        if (configured == null || configured.isEmpty()) {
            return List.of();
        }

        List<JavaHomeCandidate> candidates = new ArrayList<>();
        for (String candidate : configured) {
            String normalized = normalizePathString(candidate);
            if (normalized == null) {
                continue;
            }
            Path javaHomePath = Path.of(normalized);
            if (!Files.exists(javaHomePath) || !Files.isDirectory(javaHomePath)) {
                continue;
            }

            Integer javaMajor = parseJavaMajorFromHome(javaHomePath);
            if (javaMajor == null) {
                continue;
            }
            candidates.add(new JavaHomeCandidate(normalized, javaMajor));
        }
        return candidates;
    }

    private List<String> toJavaHomePaths(List<JavaHomeCandidate> candidates) {
        List<String> result = new ArrayList<>();
        for (JavaHomeCandidate candidate : candidates) {
            result.add(candidate.path());
        }
        return result;
    }

    private Map<String, String> resolveGradleBaseEnvironment(Path workspaceRoot) {
        Map<String, String> env = new HashMap<>();
        if (!buildCommandProperties.isIsolatedExecution()) {
            return env;
        }

        Path gradleUserHome = workspaceRoot.resolve(buildCommandProperties.getGradleUserHomeDir())
                .toAbsolutePath()
                .normalize();
        try {
            Files.createDirectories(gradleUserHome);
            env.put("GRADLE_USER_HOME", gradleUserHome.toString());
        } catch (IOException e) {
            log.warn("[BUILD] Failed to create GRADLE_USER_HOME directory. path={}", gradleUserHome, e);
        }
        return env;
    }

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

    private Map<String, String> buildEnvironment(Map<String, String> baseEnvironment, String javaHome) {
        Map<String, String> env = new HashMap<>(baseEnvironment);
        env.put("JAVA_HOME", javaHome);

        String binPath = javaHome + File.separator + "bin";
        String currentPath = Optional.ofNullable(System.getenv("PATH")).orElse("");
        String mergedPath = currentPath.isBlank() ? binPath : binPath + File.pathSeparator + currentPath;

        env.put("PATH", mergedPath);
        env.put("Path", mergedPath);
        return env;
    }

    private Optional<GradleVersion> parseGradleVersionFromWrapper(Path repoRoot) {
        Path wrapperProperties = repoRoot.resolve("gradle").resolve("wrapper").resolve("gradle-wrapper.properties");
        if (!Files.exists(wrapperProperties)) {
            return Optional.empty();
        }

        Pattern distributionPattern = Pattern.compile("gradle-([0-9]+(?:\\.[0-9]+){0,2})-(?:bin|all)\\.zip");
        try {
            for (String line : Files.readAllLines(wrapperProperties, StandardCharsets.UTF_8)) {
                String trimmed = line == null ? "" : line.trim();
                if (!trimmed.startsWith("distributionUrl=")) {
                    continue;
                }
                Matcher matcher = distributionPattern.matcher(trimmed);
                if (!matcher.find()) {
                    return Optional.empty();
                }

                String rawVersion = matcher.group(1);
                String[] parts = rawVersion.split("\\.");
                int major = parseIntOrZero(parts, 0);
                int minor = parseIntOrZero(parts, 1);
                int patch = parseIntOrZero(parts, 2);

                if (major <= 0) {
                    return Optional.empty();
                }
                return Optional.of(new GradleVersion(major, minor, patch));
            }
        } catch (Exception e) {
            log.debug("[BUILD] Failed to parse gradle wrapper version. repoRoot={}", repoRoot, e);
        }
        return Optional.empty();
    }

    private int parseIntOrZero(String[] parts, int index) {
        if (parts == null || index >= parts.length) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[index]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private Integer parseJavaMajorFromHome(Path javaHome) {
        Path releaseFile = javaHome.resolve("release");
        if (Files.exists(releaseFile)) {
            try {
                for (String line : Files.readAllLines(releaseFile, StandardCharsets.UTF_8)) {
                    String trimmed = line == null ? "" : line.trim();
                    if (!trimmed.startsWith("JAVA_VERSION=")) {
                        continue;
                    }
                    int firstQuote = trimmed.indexOf('"');
                    int lastQuote = trimmed.lastIndexOf('"');
                    if (firstQuote >= 0 && lastQuote > firstQuote) {
                        String versionString = trimmed.substring(firstQuote + 1, lastQuote);
                        Integer parsed = toMajorVersion(versionString);
                        if (parsed != null) {
                            return parsed;
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("[BUILD] Failed to read JDK release file. javaHome={}", javaHome, e);
            }
        }

        String fallback = javaHome.toString();
        Matcher matcher = Pattern.compile("(?:jdk|java)[-_]?([0-9]{1,2})", Pattern.CASE_INSENSITIVE).matcher(fallback);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Integer toMajorVersion(String rawVersion) {
        if (rawVersion == null || rawVersion.isBlank()) {
            return null;
        }

        String version = rawVersion.trim();
        if (version.startsWith("1.")) {
            String[] parts = version.split("\\.");
            if (parts.length > 1) {
                try {
                    return Integer.parseInt(parts[1]);
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
            return null;
        }

        int dot = version.indexOf('.');
        String majorPart = dot >= 0 ? version.substring(0, dot) : version;
        try {
            return Integer.parseInt(majorPart);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int preferredJavaMajor(GradleVersion version) {
        if (version.major() <= 6) {
            return 11;
        }
        if (version.major() == 7) {
            return version.minor() < 3 ? 11 : 17;
        }
        if (version.major() == 8) {
            return version.minor() < 5 ? 17 : 21;
        }
        return 21;
    }

    private int compatibilityTier(GradleVersion version, int javaMajor) {
        if (version.major() <= 6) {
            if (javaMajor <= 11) return 4;
            if (javaMajor <= 16) return 3;
            return 1;
        }

        if (version.major() == 7) {
            if (version.minor() < 3) {
                if (javaMajor <= 11) return 4;
                if (javaMajor <= 16) return 3;
                if (javaMajor == 17) return 2;
                return 1;
            }
            if (javaMajor <= 17) return 4;
            if (javaMajor <= 20) return 3;
            return 1;
        }

        if (version.major() == 8) {
            if (version.minor() < 5) {
                if (javaMajor <= 17) return 4;
                if (javaMajor <= 20) return 3;
                return 2;
            }
            if (javaMajor <= 21) return 4;
            if (javaMajor <= 23) return 3;
            return 2;
        }

        if (javaMajor >= 21) return 4;
        if (javaMajor >= 17) return 3;
        return 2;
    }

    private String normalizePathString(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return null;
        }
        try {
            return Path.of(rawPath.trim()).toAbsolutePath().normalize().toString();
        } catch (Exception e) {
            return rawPath.trim();
        }
    }

    private record MavenJavaSelection(Optional<Integer> requiredJavaMajor, List<String> javaHomes) {
    }

    private record JavaHomeCandidate(String path, int major) {
    }

    private record GradleVersion(int major, int minor, int patch) {
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

    private List<BuildModuleManifest> parseGradleDump(Path repoRoot, String output, List<BuildFailure> failures) {
        Pattern p = Pattern.compile("^OSS_DOC_DUMP=(\\{.*\\})$", Pattern.MULTILINE);
        Matcher m = p.matcher(output);

        List<BuildModuleManifest> modules = new ArrayList<>();
        while (m.find()) {
            try {
                Map<String, Object> map = objectMapper.readValue(m.group(1), Map.class);

                List<String> sourceRoots = readPathList(map, "sourceRoots", repoRoot);
                List<String> testRoots = readPathList(map, "testRoots", repoRoot);
                List<String> resourceRoots = readPathList(map, "resourceRoots", repoRoot);
                List<String> classesDirs = readPathList(map, "classesDirs", repoRoot);
                List<String> compileClasspath = readPathList(map, "compileClasspath", repoRoot);
                List<String> runtimeClasspath = readPathList(map, "runtimeClasspath", repoRoot);

                /**
                 * OK ??classesDirs? ? ??ASM ? ??
                 * PARTIAL ??sourceRoots??? ??AST ??
                 * FAILED ??? ? ?
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

    private List<String> readPathList(Map<String, Object> map, String key, Path repoRoot) {
        Object rawValue = map.get(key);
        if (!(rawValue instanceof List<?> values)) {
            return List.of();
        }

        List<String> pathValues = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof String path && !path.isBlank()) {
                pathValues.add(path);
            }
        }

        return buildPathNormalizer.toAbsolutePaths(repoRoot, pathValues);
    }

    private BuildManifest resolveMaven(String runId, Path repoRoot, Path workspaceRoot, boolean wrapperUsed, Path tmpDir) {
        List<BuildFailure> failures = new ArrayList<>();
        List<BuildModuleManifest> modules = new ArrayList<>();

        try {
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
}

