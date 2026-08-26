package com.example.ossdoc.domain.build.support;

import com.example.ossdoc.domain.build.enums.BuildToolKind;
import com.example.ossdoc.global.config.BuildCommandProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 역할:
 * 빌드 실행에 필요한 JDK 선택/호환성 판단 규칙을 제공한다.
 *
 * 책임:
 * 1) 설정된 JAVA_HOME 후보 로드 및 버전 파싱
 * 2) Gradle/Maven별 우선순위에 따라 후보 정렬
 * 3) 빌드 로그에서 Java 호환성 실패 신호 감지
 * 4) JAVA_HOME/PATH 실행 환경 맵 구성
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BuildToolchainSupport {

    private final BuildCommandProperties buildCommandProperties;

    /**
     * 역할:
     * Gradle 실행용 JAVA_HOME 후보 목록을 우선순위대로 반환한다.
     *
     * 책임:
     * gradle-wrapper 버전을 참고해 호환성이 높은 JDK가 먼저 오도록 정렬한다.
     */
    public List<String> resolveJavaHomeCandidatesForGradle(Path repoRoot) {
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

    /**
     * 역할:
     * Maven 실행용 JAVA_HOME 후보 목록을 우선순위대로 반환한다.
     *
     * 책임:
     * pom 분석으로 얻은 requiredJava를 기준으로 적합한 JDK를 앞쪽에 배치한다.
     */
    public List<String> resolveJavaHomeCandidatesForMaven(Optional<Integer> requiredJavaMajor) {
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

    /**
     * 역할:
     * 실행 로그에서 Java 버전 불일치/호환성 문제 신호를 탐지한다.
     *
     * 책임:
     * 공통 신호 + Gradle 전용 + Maven 전용 문구를 종합해 재시도 여부 판단 근거를 제공한다.
     */
    public Optional<String> detectJavaCompatibilityReason(BuildToolKind buildToolKind, ProcessRunner.Result result) {
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
            // Gradle toolchain/JDK 미스매치에서 자주 나오는 문구를 재시도 신호로 사용
            List<String> gradleSignals = List.of(
                    "this version of gradle supports java",
                    "minimum supported gradle version",
                    "cannot find a java installation on your machine matching",
                    "toolchain download repositories have not been configured",
                    "no matching toolchains found for requested specification"
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

    /**
     * 역할:
     * 전달된 JAVA_HOME이 현재 런타임 JVM 경로와 동일한지 판단한다.
     *
     * 책임:
     * JAVA_HOME/System java.home 기준으로 baseline 폴백 필요 여부를 지원한다.
     */
    public boolean isCurrentRuntimeJavaHome(String javaHome) {
        String normalized = normalizePathString(javaHome);
        if (normalized == null) {
            return false;
        }
        String currentJavaHome = normalizePathString(System.getenv("JAVA_HOME"));
        String currentRuntimeHome = normalizePathString(System.getProperty("java.home"));
        return normalized.equalsIgnoreCase(currentJavaHome) || normalized.equalsIgnoreCase(currentRuntimeHome);
    }

    /**
     * 역할:
     * 특정 JAVA_HOME으로 프로세스를 실행하기 위한 환경변수 맵을 구성한다.
     *
     * 책임:
     * JAVA_HOME 설정과 함께 PATH/Path에 해당 JDK bin을 우선 주입한다.
     */
    public Map<String, String> buildEnvironment(Map<String, String> baseEnvironment, String javaHome) {
        Map<String, String> env = new HashMap<>(baseEnvironment);
        env.put("JAVA_HOME", javaHome);

        String binPath = javaHome + File.separator + "bin";
        String currentPath = Optional.ofNullable(System.getenv("PATH")).orElse("");
        String mergedPath = currentPath.isBlank() ? binPath : binPath + File.pathSeparator + currentPath;

        env.put("PATH", mergedPath);
        env.put("Path", mergedPath);
        return env;
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

    private record JavaHomeCandidate(String path, int major) {
    }

    private record GradleVersion(int major, int minor, int patch) {
    }
}
