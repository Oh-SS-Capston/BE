package com.example.ossdoc.domain.license.support;

import com.example.ossdoc.domain.license.enums.LicenseCandidateSource;
import com.example.ossdoc.domain.license.enums.LicenseEvidenceType;
import com.example.ossdoc.domain.license.model.LicenseProfile;
import com.example.ossdoc.domain.license.model.ProjectLicenseCandidate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 저장소 루트에서 대표 라이선스 후보를 수집합니다.
 *
 * <p>역할:
 * LICENSE, COPYING, NOTICE, README, pom.xml, build.gradle 같은 루트 파일을 읽고
 * LicenseCatalog를 이용해 SPDX 프로필 후보로 변환합니다.
 *
 * <p>중요한 범위:
 * 이 클래스는 "최종 대표 라이선스 결정"을 하지 않습니다.
 * 파일별 후보와 근거만 수집하고, 후보 간 우선순위나 충돌 판단은 다음 단계의 서비스가 담당합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectLicenseCandidateCollector {

    /**
     * 라이선스 문구가 여러 줄에 나뉘어 있는 경우를 감지하기 위해 함께 묶어 볼 최대 줄 수입니다.
     * 예: GPL 전문은 "GNU GENERAL PUBLIC LICENSE"와 "Version 3"이 다른 줄에 있을 수 있습니다.
     */
    private static final int MAX_SCAN_WINDOW_LINES = 4;

    /**
     * 화면과 JSON 근거에 담을 snippet의 최대 길이입니다.
     * 근거는 짧게 보여주기 위한 목적이므로 파일 전체를 담지 않습니다.
     */
    private static final int SNIPPET_LIMIT = 300;

    /**
     * 대표 라이선스 후보를 찾기 위해 루트에서 확인할 파일 목록입니다.
     * 같은 물리 파일이 여러 후보명과 매칭되면 한 번만 읽도록 아래 수집 로직에서 중복을 제거합니다.
     */
    private static final List<CandidateFile> CANDIDATE_FILES = List.of(
            new CandidateFile("LICENSE", LicenseCandidateSource.LICENSE_FILE, LicenseEvidenceType.LICENSE_FILE),
            new CandidateFile("LICENSE.md", LicenseCandidateSource.LICENSE_FILE, LicenseEvidenceType.LICENSE_FILE),
            new CandidateFile("LICENSE.txt", LicenseCandidateSource.LICENSE_FILE, LicenseEvidenceType.LICENSE_FILE),
            new CandidateFile("LICENCE", LicenseCandidateSource.LICENSE_FILE, LicenseEvidenceType.LICENSE_FILE),
            new CandidateFile("LICENCE.md", LicenseCandidateSource.LICENSE_FILE, LicenseEvidenceType.LICENSE_FILE),
            new CandidateFile("LICENCE.txt", LicenseCandidateSource.LICENSE_FILE, LicenseEvidenceType.LICENSE_FILE),
            new CandidateFile("COPYING", LicenseCandidateSource.COPYING_FILE, LicenseEvidenceType.COPYING_FILE),
            new CandidateFile("COPYING.md", LicenseCandidateSource.COPYING_FILE, LicenseEvidenceType.COPYING_FILE),
            new CandidateFile("COPYING.txt", LicenseCandidateSource.COPYING_FILE, LicenseEvidenceType.COPYING_FILE),
            new CandidateFile("NOTICE", LicenseCandidateSource.NOTICE_FILE, LicenseEvidenceType.NOTICE_FILE),
            new CandidateFile("NOTICE.md", LicenseCandidateSource.NOTICE_FILE, LicenseEvidenceType.NOTICE_FILE),
            new CandidateFile("NOTICE.txt", LicenseCandidateSource.NOTICE_FILE, LicenseEvidenceType.NOTICE_FILE),
            new CandidateFile("README", LicenseCandidateSource.README_FILE, LicenseEvidenceType.README),
            new CandidateFile("README.md", LicenseCandidateSource.README_FILE, LicenseEvidenceType.README),
            new CandidateFile("README.txt", LicenseCandidateSource.README_FILE, LicenseEvidenceType.README),
            new CandidateFile("README.rst", LicenseCandidateSource.README_FILE, LicenseEvidenceType.README),
            new CandidateFile("README.adoc", LicenseCandidateSource.README_FILE, LicenseEvidenceType.README),
            new CandidateFile("pom.xml", LicenseCandidateSource.MAVEN_POM, LicenseEvidenceType.POM_LICENSES),
            new CandidateFile("build.gradle", LicenseCandidateSource.GRADLE_BUILD_FILE, LicenseEvidenceType.GRADLE_BUILD_FILE),
            new CandidateFile("build.gradle.kts", LicenseCandidateSource.GRADLE_BUILD_FILE, LicenseEvidenceType.GRADLE_BUILD_FILE)
    );

    private final LicenseCatalog licenseCatalog;

    /**
     * 저장소 루트에서 대표 라이선스 후보 목록을 수집합니다.
     *
     * @param repoRoot 분석 대상 저장소 루트 경로
     * @return 파일별 대표 라이선스 후보 목록. 후보가 없으면 빈 리스트를 반환합니다.
     */
    public List<ProjectLicenseCandidate> collect(Path repoRoot) {
        if (repoRoot == null || !Files.isDirectory(repoRoot)) {
            return List.of();
        }

        Map<String, Path> rootFilesByName = listRootFilesByLowerName(repoRoot);
        if (rootFilesByName.isEmpty()) {
            return List.of();
        }

        List<ProjectLicenseCandidate> candidates = new ArrayList<>();
        Set<Path> scannedFiles = new LinkedHashSet<>();

        for (CandidateFile candidateFile : CANDIDATE_FILES) {
            Path file = rootFilesByName.get(candidateFile.fileName().toLowerCase(Locale.ROOT));
            if (file == null || !scannedFiles.add(file.toAbsolutePath().normalize())) {
                continue;
            }

            scanFile(repoRoot, file, candidateFile)
                    .ifPresent(candidates::add);
        }

        candidates.sort(Comparator
                .comparing((ProjectLicenseCandidate c) -> c.getSource().ordinal())
                .thenComparing(ProjectLicenseCandidate::getPath));

        return List.copyOf(candidates);
    }

    private Map<String, Path> listRootFilesByLowerName(Path repoRoot) {
        try (Stream<Path> stream = Files.list(repoRoot)) {
            Map<String, Path> result = new LinkedHashMap<>();
            stream.filter(Files::isRegularFile)
                    .forEach(path -> result.putIfAbsent(
                            path.getFileName().toString().toLowerCase(Locale.ROOT),
                            path
                    ));
            return result;
        } catch (IOException e) {
            log.warn("[LICENSE] 저장소 루트 파일 목록을 읽지 못했습니다. repoRoot={}", repoRoot, e);
            return Map.of();
        }
    }

    private Optional<ProjectLicenseCandidate> scanFile(Path repoRoot, Path file, CandidateFile candidateFile) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[LICENSE] 라이선스 후보 파일을 읽지 못했습니다. file={}", file, e);
            return Optional.empty();
        }

        Optional<LineMatch> match = findKnownLicenseMatch(lines);
        if (match.isPresent()) {
            return Optional.of(toCandidate(repoRoot, file, candidateFile, match.get()));
        }

        if (shouldKeepUnknownLicenseFile(candidateFile.source())) {
            return firstNonBlankLine(lines)
                    .map(firstLine -> toUnknownCandidate(repoRoot, file, candidateFile, firstLine));
        }

        return Optional.empty();
    }

    private Optional<LineMatch> findKnownLicenseMatch(List<String> lines) {
        for (int startIndex = 0; startIndex < lines.size(); startIndex++) {
            if (lines.get(startIndex).isBlank()) {
                continue;
            }

            int maxEndExclusive = Math.min(lines.size(), startIndex + MAX_SCAN_WINDOW_LINES);
            for (int endExclusive = startIndex + 1; endExclusive <= maxEndExclusive; endExclusive++) {
                String snippet = compactSnippet(lines.subList(startIndex, endExclusive));
                if (snippet.isBlank()) {
                    continue;
                }

                LicenseProfile profile = licenseCatalog.resolveTextOrUnknown(snippet);
                if (!"UNKNOWN".equals(profile.getSpdxId())) {
                    return Optional.of(new LineMatch(
                            profile,
                            startIndex + 1,
                            endExclusive,
                            limitSnippet(snippet)
                    ));
                }
            }
        }
        return Optional.empty();
    }

    private Optional<LineSnippet> firstNonBlankLine(List<String> lines) {
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line != null && !line.isBlank()) {
                return Optional.of(new LineSnippet(index + 1, limitSnippet(line.trim())));
            }
        }
        return Optional.empty();
    }

    private ProjectLicenseCandidate toCandidate(
            Path repoRoot,
            Path file,
            CandidateFile candidateFile,
            LineMatch match
    ) {
        String spdxId = match.profile().getSpdxId();
        return ProjectLicenseCandidate.builder()
                .profile(match.profile())
                .source(candidateFile.source())
                .evidenceType(candidateFile.evidenceType())
                .path(toRepoRelativePath(repoRoot, file))
                .startLine(match.startLine())
                .endLine(match.endLine())
                .snippet(match.snippet())
                .rawLicenseText(match.snippet())
                .confidence(confidenceFor(candidateFile.source(), true))
                .note(candidateFile.source().getLabel() + "에서 " + spdxId + " 후보를 감지했습니다.")
                .build();
    }

    private ProjectLicenseCandidate toUnknownCandidate(
            Path repoRoot,
            Path file,
            CandidateFile candidateFile,
            LineSnippet firstLine
    ) {
        return ProjectLicenseCandidate.builder()
                .profile(licenseCatalog.unknownProfile())
                .source(candidateFile.source())
                .evidenceType(LicenseEvidenceType.UNKNOWN_LICENSE_FILE)
                .path(toRepoRelativePath(repoRoot, file))
                .startLine(firstLine.lineNumber())
                .endLine(firstLine.lineNumber())
                .snippet(firstLine.snippet())
                .rawLicenseText(firstLine.snippet())
                .confidence(confidenceFor(candidateFile.source(), false))
                .note(candidateFile.source().getLabel() + "은 존재하지만 지원하는 SPDX 라이선스로 식별하지 못했습니다.")
                .build();
    }

    private boolean shouldKeepUnknownLicenseFile(LicenseCandidateSource source) {
        return source == LicenseCandidateSource.LICENSE_FILE
                || source == LicenseCandidateSource.COPYING_FILE;
    }

    private double confidenceFor(LicenseCandidateSource source, boolean knownLicense) {
        if (!knownLicense) {
            return 0.20;
        }
        return switch (source) {
            case LICENSE_FILE -> 0.98;
            case COPYING_FILE -> 0.95;
            case MAVEN_POM -> 0.85;
            case GRADLE_BUILD_FILE -> 0.75;
            case README_FILE -> 0.65;
            case NOTICE_FILE -> 0.60;
        };
    }

    private String compactSnippet(List<String> lines) {
        String joined = String.join(" ", lines);
        return joined.replaceAll("\\s+", " ").trim();
    }

    private String limitSnippet(String snippet) {
        if (snippet == null) {
            return "";
        }
        String trimmed = snippet.trim();
        if (trimmed.length() <= SNIPPET_LIMIT) {
            return trimmed;
        }
        return trimmed.substring(0, SNIPPET_LIMIT);
    }

    private String toRepoRelativePath(Path repoRoot, Path file) {
        try {
            return repoRoot.toAbsolutePath()
                    .normalize()
                    .relativize(file.toAbsolutePath().normalize())
                    .toString()
                    .replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return file.getFileName().toString();
        }
    }

    private record CandidateFile(
            String fileName,
            LicenseCandidateSource source,
            LicenseEvidenceType evidenceType
    ) {
    }

    private record LineMatch(
            LicenseProfile profile,
            int startLine,
            int endLine,
            String snippet
    ) {
    }

    private record LineSnippet(
            int lineNumber,
            String snippet
    ) {
    }
}
