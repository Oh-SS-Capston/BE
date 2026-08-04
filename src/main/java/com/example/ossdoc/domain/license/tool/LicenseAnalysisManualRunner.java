package com.example.ossdoc.domain.license.tool;

import com.example.ossdoc.domain.license.artifact.output.LicenseAnalysisJson;
import com.example.ossdoc.domain.license.artifact.output.LicenseEvidenceJson;
import com.example.ossdoc.domain.license.artifact.output.LicenseReviewItemJson;
import com.example.ossdoc.domain.license.artifact.output.ProjectLicenseJson;
import com.example.ossdoc.domain.license.service.LicenseAnalysisService;
import com.example.ossdoc.domain.license.support.LicenseAnalysisJsonAssembler;
import com.example.ossdoc.domain.license.support.LicenseCatalog;
import com.example.ossdoc.domain.license.support.ProjectLicenseCandidateCollector;
import com.example.ossdoc.domain.license.support.ProjectLicenseSelector;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 대표 라이선스 분석을 로컬 저장소 경로에 대해 수동 실행하는 개발용 러너입니다.
 *
 * <p>역할:
 * 실제 GitHub 오픈소스 저장소를 로컬에 clone한 뒤, 파이프라인 연결 없이 라이선스 분석 결과를 눈으로 확인합니다.
 * 이 클래스는 Spring Boot 애플리케이션을 띄우지 않고 필요한 객체를 직접 조립합니다.
 *
 * <p>중요:
 * - Artifact 저장을 하지 않습니다.
 * - DB/S3를 사용하지 않습니다.
 * - RunPipelineExecutor, RunStage, Controller와 연결하지 않습니다.
 * - 나중에 파이프라인 연결 전에 분석 품질을 확인하기 위한 임시/개발용 진입점입니다.
 *
 * <p>실행 인자:
 * args[0] = 분석할 로컬 저장소 루트 경로
 * args[1] = 선택 입력. JSON runId에 넣을 값. 생략하면 manual_license_run_yyyyMMddHHmmss 형식으로 생성합니다.
 */
public class LicenseAnalysisManualRunner {

    private static final DateTimeFormatter RUN_ID_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /**
     * 수동 검증 실행 진입점입니다.
     * repoRoot를 받아 대표 라이선스 요약, 검토 항목, 근거 목록, 전체 JSON을 순서대로 출력합니다.
     */
    public static void main(String[] args) throws JsonProcessingException {
        if (args.length == 0) {
            printUsage();
            return;
        }

        Path repoRoot = Path.of(args[0]).toAbsolutePath().normalize();
        if (!Files.isDirectory(repoRoot)) {
            System.err.println("[LICENSE-MANUAL] repoRoot가 디렉터리가 아닙니다: " + repoRoot);
            printUsage();
            return;
        }

        String runId = args.length >= 2 && !args[1].isBlank()
                ? args[1].trim()
                : defaultRunId();

        LicenseAnalysisService licenseAnalysisService = buildService();
        LicenseAnalysisJson result = licenseAnalysisService.analyze(runId, repoRoot);

        printHumanReadableSummary(repoRoot, result);
        printPrettyJson(result);
    }

    /**
     * Spring 컨테이너 없이 라이선스 분석 서비스와 하위 컴포넌트를 직접 조립합니다.
     * 이 메서드를 보면 실제 파이프라인 연결 시 필요한 의존성 흐름을 한눈에 볼 수 있습니다.
     */
    private static LicenseAnalysisService buildService() {
        LicenseCatalog licenseCatalog = new LicenseCatalog();
        return new LicenseAnalysisService(
                new ProjectLicenseCandidateCollector(licenseCatalog),
                new ProjectLicenseSelector(licenseCatalog),
                new LicenseAnalysisJsonAssembler(licenseCatalog)
        );
    }

    /**
     * 사용자가 runId를 넘기지 않았을 때 수동 실행용 runId를 만듭니다.
     * 실제 RepoRun ID가 아니라 눈검증 JSON을 구분하기 위한 표시용 값입니다.
     */
    private static String defaultRunId() {
        return "manual_license_run_" + LocalDateTime.now().format(RUN_ID_TIME_FORMATTER);
    }

    /**
     * CLI 사용 방법을 출력합니다.
     * 기존 파이프라인이 아니라 로컬 repoRoot를 직접 넘긴다는 점을 명확히 보여줍니다.
     */
    private static void printUsage() {
        System.err.println("Usage:");
        System.err.println("  java com.example.ossdoc.domain.license.tool.LicenseAnalysisManualRunner <repoRoot> [runId]");
        System.err.println();
        System.err.println("Example:");
        System.err.println("  java com.example.ossdoc.domain.license.tool.LicenseAnalysisManualRunner C:\\temp\\spring-petclinic manual-petclinic");
    }

    /**
     * 사람이 빠르게 확인할 수 있는 요약을 먼저 출력합니다.
     * 전체 JSON을 보기 전에 대표 SPDX, 검토 필요 여부, 근거 위치를 바로 확인하기 위한 출력입니다.
     */
    private static void printHumanReadableSummary(Path repoRoot, LicenseAnalysisJson result) {
        System.out.println();
        System.out.println("=== 대표 라이선스 수동 분석 요약 ===");
        System.out.println("repoRoot: " + repoRoot);
        System.out.println("runId: " + result.getRunId());
        System.out.println("analysisScope: " + result.getAnalysisScope());
        System.out.println("generatedAt: " + result.getGeneratedAt());

        printProjectLicense(result.getProjectLicense());
        printReviewItems(result.getReviewItems());
        printEvidences(result.getEvidences());
        printDisplayWarnings(result);
    }

    /**
     * 대표 라이선스 카드에 들어갈 핵심 값을 출력합니다.
     */
    private static void printProjectLicense(ProjectLicenseJson projectLicense) {
        System.out.println();
        System.out.println("[대표 라이선스]");
        if (projectLicense == null) {
            System.out.println("- projectLicense가 비어 있습니다.");
            return;
        }

        System.out.println("- spdxId: " + projectLicense.getSpdxId());
        System.out.println("- displayName: " + projectLicense.getDisplayName());
        System.out.println("- family: " + projectLicense.getFamily());
        System.out.println("- reviewLevel: " + projectLicense.getReviewLevel());
        System.out.println("- confidence: " + projectLicense.getConfidence());
        System.out.println("- evidenceIds: " + safeList(projectLicense.getEvidenceIds()));
    }

    /**
     * UNKNOWN, 후보 충돌, HIGH 검토 수준처럼 사람이 확인해야 하는 항목을 출력합니다.
     */
    private static void printReviewItems(List<LicenseReviewItemJson> reviewItems) {
        System.out.println();
        System.out.println("[검토 필요 항목]");
        List<LicenseReviewItemJson> safeItems = safeList(reviewItems);
        if (safeItems.isEmpty()) {
            System.out.println("- 없음");
            return;
        }

        for (LicenseReviewItemJson item : safeItems) {
            System.out.println("- type: " + item.getType());
            System.out.println("  title: " + item.getTitle());
            System.out.println("  message: " + item.getMessage());
            System.out.println("  evidenceIds: " + safeList(item.getEvidenceIds()));
        }
    }

    /**
     * 라이선스 판단에 사용된 파일 위치와 snippet을 출력합니다.
     * 사용자가 실제 파일을 열어 눈으로 판단이 맞는지 검증할 때 가장 중요한 영역입니다.
     */
    private static void printEvidences(List<LicenseEvidenceJson> evidences) {
        System.out.println();
        System.out.println("[근거 목록]");
        List<LicenseEvidenceJson> safeEvidences = safeList(evidences);
        if (safeEvidences.isEmpty()) {
            System.out.println("- 없음");
            return;
        }

        for (LicenseEvidenceJson evidence : safeEvidences) {
            System.out.println("- evidenceId: " + evidence.getEvidenceId());
            System.out.println("  type: " + evidence.getEvidenceType());
            System.out.println("  path: " + evidence.getPath());
            System.out.println("  lines: " + evidence.getStartLine() + "-" + evidence.getEndLine());
            System.out.println("  confidence: " + evidence.getConfidence());
            System.out.println("  snippet: " + evidence.getSnippet());
            System.out.println("  attrs: " + safeMap(evidence.getAttrs()));
        }
    }

    /**
     * 화면 표시 정책에서 경고 메시지만 따로 출력합니다.
     * 대표 라이선스 MVP 범위 안내와 수동 확인 필요 사유를 빠르게 볼 수 있습니다.
     */
    private static void printDisplayWarnings(LicenseAnalysisJson result) {
        System.out.println();
        System.out.println("[표시 경고]");
        if (result.getDisplayPolicy() == null || safeList(result.getDisplayPolicy().getWarnings()).isEmpty()) {
            System.out.println("- 없음");
            return;
        }

        for (String warning : safeList(result.getDisplayPolicy().getWarnings())) {
            System.out.println("- " + warning);
        }
    }

    /**
     * 전체 LicenseAnalysisJson을 pretty JSON으로 출력합니다.
     * 프론트 또는 Artifact 저장 결과와 동일한 구조를 눈으로 확인하기 위한 최종 출력입니다.
     */
    private static void printPrettyJson(LicenseAnalysisJson result) throws JsonProcessingException {
        ObjectMapper objectMapper = new ObjectMapper()
                .findAndRegisterModules()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        System.out.println();
        System.out.println("=== license_analysis.json ===");
        System.out.println(objectMapper.writeValueAsString(result));
    }

    /**
     * 출력용 리스트 null 방어 helper입니다.
     * null 목록을 빈 목록처럼 보여주면 수동 검증 중 불필요한 예외를 피할 수 있습니다.
     */
    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    /**
     * 출력용 Map null 방어 helper입니다.
     * attrs가 없으면 빈 Map으로 보여줘 근거 출력 형식을 일정하게 유지합니다.
     */
    private static Map<String, Object> safeMap(Map<String, Object> values) {
        return values == null ? Map.of() : values;
    }
}
