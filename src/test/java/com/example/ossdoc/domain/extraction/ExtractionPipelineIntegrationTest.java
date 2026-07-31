package com.example.ossdoc.domain.extraction;

import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import com.example.ossdoc.domain.artifact.service.ArtifactService;
import com.example.ossdoc.domain.build.dto.json.BuildManifest;
import com.example.ossdoc.domain.build.dto.json.BuildModuleManifest;
import com.example.ossdoc.domain.build.enums.BuildMode;
import com.example.ossdoc.domain.build.enums.BuildToolKind;
import com.example.ossdoc.domain.build.support.RepoRootResolver;
import com.example.ossdoc.domain.extraction.dto.request.FactsExtractRequest;
import com.example.ossdoc.domain.extraction.dto.response.FactsExtractResponse;
import com.example.ossdoc.domain.extraction.enums.ExtractionMode;
import com.example.ossdoc.domain.extraction.facade.DefaultFactsExtractionFacade;
import com.example.ossdoc.domain.extraction.service.composer.DefaultFactsComposer;
import com.example.ossdoc.domain.extraction.service.extractor.AsmBytecodeFactsExtractor;
import com.example.ossdoc.domain.extraction.service.extractor.ChunkFactsExtractionCoordinator;
import com.example.ossdoc.domain.extraction.service.extractor.ExtractionContextFactory;
import com.example.ossdoc.domain.extraction.service.extractor.JavaParserAstFactsExtractor;
import com.example.ossdoc.domain.extraction.service.extractor.MetaInfServiceScanner;
import com.example.ossdoc.domain.extraction.service.extractor.ReadmeObservationScanner;
import com.example.ossdoc.domain.extraction.service.support.merge.ExtractionMergeSupport;
import com.example.ossdoc.domain.extraction.service.support.planning.ChunkPlanner;
import com.example.ossdoc.domain.extraction.service.support.preflight.BuildManifestLoader;
import com.example.ossdoc.domain.extraction.service.support.preflight.BuildOutputVerifier;
import com.example.ossdoc.domain.extraction.service.support.preflight.BytecodeAvailabilityChecker;
import com.example.ossdoc.domain.extraction.service.support.preflight.ExtractionModeResolver;
import com.example.ossdoc.domain.extraction.service.support.preflight.ExtractionPreflightChecker;
import com.example.ossdoc.domain.extraction.service.support.util.ExtractionClock;
import com.example.ossdoc.domain.extraction.service.writer.DefaultFactsWriter;
import com.example.ossdoc.domain.extraction.service.writer.FactsResponseFactory;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.repository.RepoRunRepository;
import com.example.ossdoc.domain.run.support.WorkspaceManager;
import com.example.ossdoc.global.properties.WorkspaceProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Extraction 파이프라인 통합 테스트.
 *
 * <p>각 테스트마다 임시 Java 프로젝트와 build_manifest.json을 생성한다.</p>
 *
 * <p>컨텍스트 준비, Preflight, 청크 계획, AST/ASM 추출, 병합 및
 * facts 구성은 실제 구현으로 실행한다. Artifact 저장만 Mock으로
 * 대체한다.</p>
 *
 * <p>특정 PC의 고정 워크스페이스나 과거 분석 결과에 의존하지 않는다.</p>
 */
@ExtendWith(SpringExtension.class)
@Import(ExtractionPipelineIntegrationTest.TestConfig.class)
class ExtractionPipelineIntegrationTest {

    private static final String TEST_RUN_ID =
            "run_extraction_pipeline_test";

    @Configuration
    @Import({
            // facade
            DefaultFactsExtractionFacade.class,

            // preflight & planning
            ExtractionPreflightChecker.class,
            BuildOutputVerifier.class,
            BytecodeAvailabilityChecker.class,
            ExtractionModeResolver.class,
            ChunkPlanner.class,

            // extractors
            ChunkFactsExtractionCoordinator.class,
            ExtractionContextFactory.class,
            JavaParserAstFactsExtractor.class,
            AsmBytecodeFactsExtractor.class,
            MetaInfServiceScanner.class,
            ReadmeObservationScanner.class,

            // merge & compose
            ExtractionMergeSupport.class,
            DefaultFactsComposer.class,

            // writer
            DefaultFactsWriter.class,
            FactsResponseFactory.class,

            // support
            BuildManifestLoader.class,
            ExtractionClock.class,
            RepoRootResolver.class,
            WorkspaceManager.class
    })
    static class TestConfig {

        @Bean
        public ObjectMapper objectMapper() {
            return new ObjectMapper()
                    .findAndRegisterModules();
        }

        @Bean
        public WorkspaceProperties workspaceProperties() {
            WorkspaceProperties properties =
                    new WorkspaceProperties();

            properties.setBaseDir(
                    Path.of(
                            System.getProperty("java.io.tmpdir"),
                            "ossdoc-tests"
                    ).toString()
            );

            return properties;
        }

        @Bean
        public RepoRunRepository repoRunRepository() {
            return mock(RepoRunRepository.class);
        }

        @Bean
        public ArtifactService artifactService() {
            return mock(ArtifactService.class);
        }
    }

    @Autowired
    private DefaultFactsExtractionFacade factsExtractionFacade;

    @Autowired
    private RepoRunRepository repoRunRepository;

    @Autowired
    private ArtifactService artifactService;

    @Autowired
    private ObjectMapper objectMapper;

    @TempDir
    Path tempDir;

    private Path testWorkspaceRoot;

    @BeforeEach
    void setUp() throws IOException {
        reset(repoRunRepository, artifactService);

        testWorkspaceRoot = createTestWorkspace();

        RepoRun mockRun = mock(RepoRun.class);

        when(mockRun.getRunId())
                .thenReturn(TEST_RUN_ID);

        when(mockRun.getRepoUrl())
                .thenReturn(
                        "https://github.com/example/creates-sample"
                );

        when(mockRun.getCommitSha())
                .thenReturn("test-commit-sha");

        when(mockRun.getWorkspaceRoot())
                .thenReturn(testWorkspaceRoot.toString());

        when(repoRunRepository.findById(TEST_RUN_ID))
                .thenReturn(Optional.of(mockRun));

        when(artifactService.saveJsonArtifact(
                any(RepoRun.class),
                any(ArtifactKind.class),
                anyString(),
                anyString(),
                any(JsonNode.class)
        )).thenReturn(mock(Artifact.class));
    }

    @Test
    @DisplayName("자동 모드 — 임시 워크스페이스로 전체 파이프라인 성공")
    void extractFacts_autoMode_succeeds() {
        FactsExtractRequest request =
                FactsExtractRequest.builder()
                        .runId(TEST_RUN_ID)
                        .mode(null)
                        .includeObservations(true)
                        .includeTests(false)
                        .failFast(false)
                        .build();

        FactsExtractResponse response =
                factsExtractionFacade.extract(request);

        assertNotNull(
                response,
                "응답이 null이면 안 됨"
        );

        assertEquals(
                TEST_RUN_ID,
                response.runId()
        );

        assertNotNull(
                response.mode(),
                "추출 모드가 자동 결정되어야 함"
        );

        assertNotNull(response.schemaVersion());

        assertNotNull(
                response.stats(),
                "stats가 null이면 안 됨"
        );

        assertTrue(
                response.stats().filesScanned() > 0,
                "스캔된 파일이 0보다 커야 함"
        );

        assertTrue(
                response.stats().types() > 0,
                "추출된 타입이 0보다 커야 함"
        );

        assertTrue(
                response.stats().relations() > 0,
                "추출된 관계가 0보다 커야 함"
        );

        printSummary(
                "자동 모드",
                response
        );
    }

    @Test
    @DisplayName("AST_ONLY 모드 — bytecode 없이 소스만 추출")
    void extractFacts_astOnly_succeeds() {
        FactsExtractRequest request =
                FactsExtractRequest.builder()
                        .runId(TEST_RUN_ID)
                        .mode(ExtractionMode.AST_ONLY)
                        .includeObservations(false)
                        .includeTests(false)
                        .failFast(false)
                        .build();

        FactsExtractResponse response =
                factsExtractionFacade.extract(request);

        assertNotNull(response);

        assertEquals(
                TEST_RUN_ID,
                response.runId()
        );

        assertEquals(
                "ast_only",
                response.mode()
        );

        assertTrue(
                response.stats().filesScanned() > 0,
                "AST 스캔 파일이 0보다 커야 함"
        );

        assertTrue(
                response.stats().types() > 0,
                "추출된 타입이 0보다 커야 함"
        );

        printSummary(
                "AST_ONLY",
                response
        );
    }

    @Test
    @DisplayName("AST_ONLY 모드 — 객체 생성 관계와 표현식 Evidence 보존")
    void extractFacts_astOnly_preservesCreatesAndExpressionEvidence() {
        FactsExtractRequest request =
                FactsExtractRequest.builder()
                        .runId(TEST_RUN_ID)
                        .mode(ExtractionMode.AST_ONLY)
                        .includeObservations(false)
                        .includeTests(false)
                        .failFast(false)
                        .build();

        FactsExtractResponse response =
                factsExtractionFacade.extract(request);

        assertNotNull(response);

        JsonNode factsJson =
                captureFactsJson();

        JsonNode relations =
                factsJson.path("relations");

        JsonNode calls =
                relations.path("calls");

        JsonNode creates =
                relations.path("creates");

        JsonNode overrides =
                relations.path("overrides");

        JsonNode accessesField =
                relations.path("accesses_field");

        JsonNode annotatedWith =
                relations.path("annotated_with");

        assertTrue(
                creates.isArray(),
                "relations.creates는 배열이어야 함"
        );

        assertTrue(
                creates.size() > 0,
                "분석 결과에 CREATES 관계가 하나 이상 있어야 함"
        );

        Set<String> createEvidenceIds =
                collectEvidenceIds(creates);

        assertFalse(
                createEvidenceIds.isEmpty(),
                "CREATES 관계에 Evidence가 연결되어야 함"
        );

        /*
         * ObjectCreationExpr가 CALLS와 CREATES로 중복 생성되는지 확인한다.
         */
        Set<String> callEvidenceIds =
                collectEvidenceIds(calls);

        Set<String> duplicatedEvidenceIds =
                new HashSet<>(createEvidenceIds);

        duplicatedEvidenceIds.retainAll(
                callEvidenceIds
        );

        assertTrue(
                duplicatedEvidenceIds.isEmpty(),
                "객체 생성 표현식 Evidence가 CALLS와 CREATES에 중복 연결되면 안 됨"
        );

        JsonNode evidenceSection =
                factsJson.path("evidence");

        boolean hasObjectCreationExpressionEvidence =
                false;

        for (String evidenceId : createEvidenceIds) {
            JsonNode evidence =
                    findEvidence(
                            evidenceSection,
                            evidenceId
                    );

            if (evidence == null) {
                continue;
            }

            String snippet =
                    evidence.path("snippet")
                            .asText("");

            if (snippet.contains("new ")) {
                hasObjectCreationExpressionEvidence = true;
                break;
            }
        }

        assertTrue(
                hasObjectCreationExpressionEvidence,
                "CREATES 관계가 new 표현식 범위의 Evidence를 참조해야 함"
        );

        long expectedRelationCount =
                arraySize(calls)
                        + arraySize(creates)
                        + arraySize(overrides)
                        + arraySize(accessesField)
                        + arraySize(annotatedWith);

        assertEquals(
                expectedRelationCount,
                factsJson.path("stats")
                        .path("relations")
                        .asLong(),
                "stats.relations에 CREATES 개수도 포함되어야 함"
        );
    }

    @Test
    @DisplayName("AST_ONLY 모드 — 어노테이션 관계와 표현식 Evidence 보존")
    void extractFacts_astOnly_preservesAnnotatedWithAndExpressionEvidence() {
        FactsExtractRequest request =
                FactsExtractRequest.builder()
                        .runId(TEST_RUN_ID)
                        .mode(ExtractionMode.AST_ONLY)
                        .includeObservations(false)
                        .includeTests(false)
                        .failFast(false)
                        .build();

        FactsExtractResponse response =
                factsExtractionFacade.extract(request);

        assertNotNull(response);

        JsonNode factsJson =
                captureFactsJson();

        JsonNode relations =
                factsJson.path("relations");

        JsonNode annotatedWith =
                relations.path("annotated_with");

        assertTrue(
                annotatedWith.isArray(),
                "relations.annotated_with는 배열이어야 함"
        );

        assertTrue(
                annotatedWith.size() >= 4,
                "타입·필드·생성자·메서드 어노테이션 관계가 생성되어야 함"
        );

        Set<String> expectedAnnotations =
                Set.of(
                        "TypeMarker",
                        "FieldMarker",
                        "ConstructorMarker",
                        "MethodMarker"
                );

        Set<String> actualAnnotations =
                new HashSet<>();

        for (JsonNode relation : annotatedWith) {
            String destination =
                    firstNonBlankText(
                            relation,
                            "dst_symbol",
                            "dstSymbol",
                            "dst_raw_ref",
                            "dstRawRef"
                    );

            if (destination != null) {
                for (String expectedAnnotation
                        : expectedAnnotations) {

                    if (destination.endsWith(
                            expectedAnnotation
                    )) {
                        actualAnnotations.add(
                                expectedAnnotation
                        );
                    }
                }
            }

            JsonNode callSiteLine =
                    relation.get("call_site_line");

            if (callSiteLine == null) {
                callSiteLine =
                        relation.get("callSiteLine");
            }

            assertTrue(
                    callSiteLine == null
                            || callSiteLine.isNull(),
                    "ANNOTATED_WITH에는 callSiteLine이 저장되면 안 됨"
            );

            JsonNode expression =
                    relation.path("attrs")
                            .path("expression");

            assertTrue(
                    expression.isTextual()
                            && expression.asText()
                            .startsWith("@"),
                    "어노테이션 원문이 attrs.expression에 저장되어야 함"
            );
        }

        assertEquals(
                expectedAnnotations,
                actualAnnotations,
                "네 종류의 선언 어노테이션 관계가 모두 생성되어야 함"
        );

        Set<String> annotationEvidenceIds =
                collectEvidenceIds(annotatedWith);

        assertFalse(
                annotationEvidenceIds.isEmpty(),
                "ANNOTATED_WITH 관계에 Evidence가 연결되어야 함"
        );

        JsonNode evidenceSection =
                factsJson.path("evidence");

        Set<String> evidenceSnippets =
                new HashSet<>();

        for (String evidenceId
                : annotationEvidenceIds) {

            JsonNode evidence =
                    findEvidence(
                            evidenceSection,
                            evidenceId
                    );

            if (evidence == null) {
                continue;
            }

            String snippet =
                    evidence.path("snippet")
                            .asText("");

            if (!snippet.isBlank()) {
                evidenceSnippets.add(snippet);
            }
        }

        for (String annotationName
                : expectedAnnotations) {

            assertTrue(
                    evidenceSnippets.stream()
                            .anyMatch(
                                    snippet ->
                                            snippet.contains(
                                                    "@"
                                                            + annotationName
                                            )
                            ),
                    annotationName
                            + " 표현식 범위의 Evidence가 존재해야 함"
            );
        }

        long expectedRelationCount =
                arraySize(relations.path("calls"))
                        + arraySize(relations.path("creates"))
                        + arraySize(relations.path("overrides"))
                        + arraySize(
                        relations.path(
                                "accesses_field"
                        )
                )
                        + arraySize(annotatedWith);

        assertEquals(
                expectedRelationCount,
                factsJson.path("stats")
                        .path("relations")
                        .asLong(),
                "stats.relations에 ANNOTATED_WITH 개수도 포함되어야 함"
        );
    }

    @Test
    @DisplayName("AST_ONLY 모드 — 메서드 호출 관계와 호출 표현식 Evidence 보존")
    void extractFacts_astOnly_preservesCallsExpressionEvidence() {
        FactsExtractRequest request =
                FactsExtractRequest.builder()
                        .runId(TEST_RUN_ID)
                        .mode(ExtractionMode.AST_ONLY)
                        .includeObservations(false)
                        .includeTests(false)
                        .failFast(false)
                        .build();

        FactsExtractResponse response =
                factsExtractionFacade.extract(request);

        assertNotNull(response);

        JsonNode factsJson =
                captureFactsJson();

        JsonNode calls =
                factsJson.path("relations")
                        .path("calls");

        assertTrue(
                calls.isArray(),
                "relations.calls는 배열이어야 함"
        );

        assertTrue(
                calls.size() > 0,
                "CALLS 관계가 하나 이상 생성되어야 함"
        );

        JsonNode evidenceSection =
                factsJson.path("evidence");

        boolean hasTargetNameCallRelation =
                false;

        boolean hasTargetNameExpressionEvidence =
                false;

        for (JsonNode relation : calls) {
            JsonNode expression =
                    relation.path("attrs")
                            .path("expression");

            if (expression.isTextual()
                    && expression.asText()
                    .contains("target.name()")) {
                hasTargetNameCallRelation = true;
            }

            JsonNode evidenceIds =
                    relation.get("evidence_ids");

            if (evidenceIds == null) {
                evidenceIds =
                        relation.get("evidenceIds");
            }

            if (evidenceIds == null
                    || !evidenceIds.isArray()) {
                continue;
            }

            for (JsonNode evidenceIdNode
                    : evidenceIds) {

                if (!evidenceIdNode.isTextual()
                        || evidenceIdNode.asText()
                        .isBlank()) {
                    continue;
                }

                JsonNode evidence =
                        findEvidence(
                                evidenceSection,
                                evidenceIdNode.asText()
                        );

                if (evidence == null) {
                    continue;
                }

                String snippet =
                        evidence.path("snippet")
                                .asText("");

                if (!snippet.contains(
                        "target.name()"
                )) {
                    continue;
                }

                hasTargetNameExpressionEvidence = true;

                assertFalse(
                        snippet.contains(
                                "Target target = new Target()"
                        ),
                        "CALLS Evidence가 메서드 전체 범위를 포함하면 안 됨"
                );

                assertFalse(
                        snippet.contains(
                                "public String callTarget()"
                        ),
                        "CALLS Evidence가 메서드 선언 전체를 가리키면 안 됨"
                );
            }
        }

        assertTrue(
                hasTargetNameCallRelation,
                "CALLS 관계의 attrs.expression에 호출 표현식이 저장되어야 함"
        );

        assertTrue(
                hasTargetNameExpressionEvidence,
                "CALLS 관계가 target.name() 호출 구문의 Evidence를 참조해야 함"
        );
    }

    @Test
    @DisplayName("AST_PLUS_BYTECODE 모드 — 어노테이션 관계 병합과 양쪽 Evidence 보존")
    void extractFacts_withBytecode_mergesAnnotatedWithOriginsAndEvidence() {
        FactsExtractRequest request =
                FactsExtractRequest.builder()
                        .runId(TEST_RUN_ID)
                        .mode(
                                ExtractionMode.AST_PLUS_BYTECODE
                        )
                        .includeObservations(false)
                        .includeTests(false)
                        .failFast(false)
                        .build();

        FactsExtractResponse response =
                factsExtractionFacade.extract(request);

        assertNotNull(response);

        assertEquals(
                "ast_and_bytecode",
                response.mode()
        );

        JsonNode factsJson =
                captureFactsJson();

        JsonNode annotatedWith =
                factsJson.path("relations")
                        .path("annotated_with");

        assertTrue(
                annotatedWith.isArray(),
                "relations.annotated_with는 배열이어야 함"
        );

        Set<String> expectedAnnotations =
                Set.of(
                        "TypeMarker",
                        "FieldMarker",
                        "ConstructorMarker",
                        "MethodMarker"
                );

        JsonNode evidenceSection =
                factsJson.path("evidence");

        for (String annotationName
                : expectedAnnotations) {

            JsonNode mergedRelation = null;
            int matchingRelationCount = 0;

            for (JsonNode relation : annotatedWith) {
                String destination =
                        firstNonBlankText(
                                relation,
                                "dst_symbol",
                                "dstSymbol",
                                "dst_raw_ref",
                                "dstRawRef"
                        );

                if (destination != null
                        && destination.endsWith(
                        annotationName
                )) {
                    mergedRelation = relation;
                    matchingRelationCount++;
                }
            }

            assertEquals(
                    1,
                    matchingRelationCount,
                    annotationName
                            + " 관계는 AST와 BYTECODE가 병합된 한 건만 존재해야 함"
            );

            assertNotNull(
                    mergedRelation,
                    annotationName
                            + " 병합 관계가 존재해야 함"
            );

            assertEquals(
                    "ast_and_bytecode",
                    mergedRelation.path("origin")
                            .asText(),
                    annotationName
                            + " 관계 origin이 AST_AND_BYTECODE여야 함"
            );

            assertEquals(
                    "resolved",
                    mergedRelation.path("resolution")
                            .path("status")
                            .asText(),
                    annotationName
                            + " 관계가 RESOLVED 상태여야 함"
            );

            JsonNode attrs =
                    mergedRelation.path("attrs");

            assertEquals(
                    "@"
                            + annotationName,
                    attrs.path("expression")
                            .asText(),
                    annotationName
                            + " AST 표현식 속성이 보존되어야 함"
            );

            assertEquals(
                    "Lsample/"
                            + annotationName
                            + ";",
                    attrs.path("descriptor")
                            .asText(),
                    annotationName
                            + " ASM descriptor가 보존되어야 함"
            );

            assertTrue(
                    attrs.path("runtime_visible")
                            .isBoolean(),
                    annotationName
                            + " runtime_visible 속성이 존재해야 함"
            );

            assertFalse(
                    attrs.path("runtime_visible")
                            .asBoolean(),
                    annotationName
                            + " 기본 CLASS retention은 runtime visible이 아니어야 함"
            );

            JsonNode evidenceIds =
                    mergedRelation.path(
                            "evidence_ids"
                    );

            assertTrue(
                    evidenceIds.isArray(),
                    annotationName
                            + " 관계의 evidence_ids는 배열이어야 함"
            );

            assertTrue(
                    evidenceIds.size() >= 2,
                    annotationName
                            + " 관계에 AST와 BYTECODE Evidence가 모두 연결되어야 함"
            );

            Set<String> evidenceTypes =
                    new HashSet<>();

            for (JsonNode evidenceIdNode
                    : evidenceIds) {

                if (!evidenceIdNode.isTextual()
                        || evidenceIdNode.asText()
                        .isBlank()) {
                    continue;
                }

                JsonNode evidence =
                        findEvidence(
                                evidenceSection,
                                evidenceIdNode.asText()
                        );

                assertNotNull(
                        evidence,
                        annotationName
                                + " 관계가 참조하는 Evidence가 존재해야 함"
                );

                String evidenceType =
                        firstNonBlankText(
                                evidence,
                                "type"
                        );

                if (evidenceType != null) {
                    evidenceTypes.add(
                            evidenceType
                    );
                }
            }

            assertTrue(
                    evidenceTypes.contains("ast"),
                    annotationName
                            + " 관계에 AST Evidence가 있어야 함"
            );

            assertTrue(
                    evidenceTypes.contains("bytecode"),
                    annotationName
                            + " 관계에 BYTECODE Evidence가 있어야 함"
            );
        }

        assertEquals(
                expectedAnnotations.size(),
                annotatedWith.size(),
                "네 ANNOTATED_WITH 관계가 중복 없이 병합되어야 함"
        );
    }

    @Test
    @DisplayName("AST_PLUS_BYTECODE 모드 — 소스와 바이트코드 추출")
    void extractFacts_withBytecode_succeeds() {
        FactsExtractRequest request =
                FactsExtractRequest.builder()
                        .runId(TEST_RUN_ID)
                        .mode(ExtractionMode.AST_PLUS_BYTECODE)
                        .includeObservations(true)
                        .includeTests(false)
                        .failFast(false)
                        .build();

        FactsExtractResponse response =
                factsExtractionFacade.extract(request);

        assertNotNull(response);

        assertEquals(
                TEST_RUN_ID,
                response.runId()
        );

        assertEquals(
                "ast_and_bytecode",
                response.mode()
        );

        assertTrue(
                response.stats().filesScanned() > 0,
                "바이트코드 모드에서 파일 스캔이 0보다 커야 함"
        );

        printSummary(
                "AST_PLUS_BYTECODE",
                response
        );
    }

    /**
     * 테스트마다 독립적인 임시 워크스페이스를 생성한다.
     *
     * <pre>
     * workspace
     * ├─ repo
     * │  ├─ build.gradle
     * │  ├─ settings.gradle
     * │  ├─ README.md
     * │  ├─ src/main/java/sample/CreatesSample.java
     * │  └─ build/classes/java/main/sample/*.class
     * └─ artifacts
     *    └─ build_manifest.json
     * </pre>
     */
    private Path createTestWorkspace() throws IOException {
        Path workspaceRoot =
                tempDir.resolve(TEST_RUN_ID);

        Path repoRoot =
                workspaceRoot.resolve("repo");

        Path sourceRoot =
                repoRoot.resolve("src/main/java");

        Path packageDirectory =
                sourceRoot.resolve("sample");

        Path artifactsRoot =
                workspaceRoot.resolve("artifacts");

        Path classesRoot =
                repoRoot.resolve(
                        "build/classes/java/main"
                );

        Files.createDirectories(packageDirectory);
        Files.createDirectories(artifactsRoot);
        Files.createDirectories(classesRoot);

        writeGradleProjectFiles(repoRoot);

        Path sourceFile =
                writeTestJavaSource(packageDirectory);

        compileTestSource(
                sourceFile,
                classesRoot
        );

        writeBuildManifest(
                artifactsRoot
        );

        return workspaceRoot;
    }

    private void writeGradleProjectFiles(
            Path repoRoot
    ) throws IOException {
        Files.writeString(
                repoRoot.resolve("settings.gradle"),
                """
                rootProject.name = 'creates-sample'
                """,
                StandardCharsets.UTF_8
        );

        Files.writeString(
                repoRoot.resolve("build.gradle"),
                """
                plugins {
                    id 'java'
                }
                """,
                StandardCharsets.UTF_8
        );

        Files.writeString(
                repoRoot.resolve("README.md"),
                """
                # Creates Sample

                ExtractionPipelineIntegrationTest에서 사용하는
                임시 Java 프로젝트입니다.
                """,
                StandardCharsets.UTF_8
        );
    }

    private Path writeTestJavaSource(
            Path packageDirectory
    ) throws IOException {
        Path sourceFile =
                packageDirectory.resolve(
                        "CreatesSample.java"
                );

        Files.writeString(
                sourceFile,
                """
                package sample;
        
                @TypeMarker
                public class CreatesSample {
        
                    @FieldMarker
                    private final Target field =
                            new Target();
        
                    @ConstructorMarker
                    public CreatesSample() {
                    }
        
                    @MethodMarker
                    public Target create() {
                        return new Target();
                    }
        
                    public String callTarget() {
                        Target target = new Target();
                        return target.name();
                    }
                }
        
                class Target {
        
                    String name() {
                        return "target";
                    }
                }
        
                @interface TypeMarker {
                }
        
                @interface FieldMarker {
                }
        
                @interface ConstructorMarker {
                }
        
                @interface MethodMarker {
                }
                """,
                StandardCharsets.UTF_8
        );

        return sourceFile;
    }

    /**
     * AST_PLUS_BYTECODE 테스트에서 사용할 class 파일을 생성한다.
     */
    private void compileTestSource(
            Path sourceFile,
            Path classesRoot
    ) {
        JavaCompiler compiler =
                ToolProvider.getSystemJavaCompiler();

        assertNotNull(
                compiler,
                "통합 테스트는 JRE가 아닌 JDK 환경에서 실행되어야 함"
        );

        int exitCode =
                compiler.run(
                        null,
                        null,
                        null,
                        "-encoding",
                        "UTF-8",
                        "-d",
                        classesRoot.toString(),
                        sourceFile.toString()
                );

        assertEquals(
                0,
                exitCode,
                "테스트용 Java 소스 컴파일에 실패함"
        );
    }

    /**
     * Preflight 검사를 통과할 수 있는 테스트용 build_manifest.json을 생성한다.
     */
    private void writeBuildManifest(
            Path artifactsRoot
    ) throws IOException {
        BuildModuleManifest moduleManifest =
                BuildModuleManifest.builder()
                        .moduleId(":")
                        .name("creates-sample")
                        .groupId("sample")
                        .artifactId("creates-sample")
                        .version("1.0.0")
                        .sourceRoots(
                                List.of("src/main/java")
                        )
                        .testRoots(List.of())
                        .resourceRoots(List.of())
                        .classesDirs(
                                List.of(
                                        "build/classes/java/main"
                                )
                        )
                        .compileClasspath(
                                List.of(
                                        "build/classes/java/main"
                                )
                        )
                        .runtimeClasspath(
                                List.of(
                                        "build/classes/java/main"
                                )
                        )
                        .status("OK")
                        .failReason(null)
                        .build();

        BuildManifest buildManifest =
                BuildManifest.builder()
                        .runId(TEST_RUN_ID)
                        .detectedAt(OffsetDateTime.now())
                        .buildTool(BuildToolKind.GRADLE)
                        .wrapperUsed(false)
                        .buildMode(BuildMode.FULL)
                        .modules(
                                List.of(moduleManifest)
                        )
                        .failures(List.of())
                        .build();

        Path manifestFile =
                artifactsRoot.resolve(
                        "build_manifest.json"
                );

        objectMapper
                .writerWithDefaultPrettyPrinter()
                .writeValue(
                        manifestFile.toFile(),
                        buildManifest
                );

        assertTrue(
                Files.exists(manifestFile),
                "테스트용 build_manifest.json이 생성되어야 함"
        );
    }

    private JsonNode captureFactsJson() {
        ArgumentCaptor<JsonNode> jsonCaptor =
                ArgumentCaptor.forClass(
                        JsonNode.class
                );

        verify(artifactService)
                .saveJsonArtifact(
                        any(RepoRun.class),
                        eq(ArtifactKind.FACTS_JSON),
                        anyString(),
                        anyString(),
                        jsonCaptor.capture()
                );

        JsonNode factsJson =
                jsonCaptor.getValue();

        assertNotNull(
                factsJson,
                "ArtifactService에 전달된 facts JSON이 null이면 안 됨"
        );

        return factsJson;
    }

    private Set<String> collectEvidenceIds(
            JsonNode relationArray
    ) {
        Set<String> result =
                new HashSet<>();

        if (relationArray == null
                || !relationArray.isArray()) {
            return result;
        }

        for (JsonNode relation : relationArray) {
            JsonNode evidenceIds =
                    relation.get("evidence_ids");

            if (evidenceIds == null) {
                evidenceIds =
                        relation.get("evidenceIds");
            }

            if (evidenceIds == null
                    || !evidenceIds.isArray()) {
                continue;
            }

            for (JsonNode evidenceId : evidenceIds) {
                if (evidenceId.isTextual()
                        && !evidenceId.asText().isBlank()) {
                    result.add(
                            evidenceId.asText()
                    );
                }
            }
        }

        return result;
    }

    private JsonNode findEvidence(
            JsonNode evidenceSection,
            String evidenceId
    ) {
        if (evidenceSection == null
                || evidenceSection.isMissingNode()
                || evidenceSection.isNull()) {
            return null;
        }

        if (evidenceSection.isObject()) {
            return evidenceSection.get(
                    evidenceId
            );
        }

        if (evidenceSection.isArray()) {
            for (JsonNode evidence : evidenceSection) {
                String actualEvidenceId =
                        firstNonBlankText(
                                evidence,
                                "id",
                                "evidence_id",
                                "evidenceId"
                        );

                if (evidenceId.equals(actualEvidenceId)) {
                    return evidence;
                }
            }
        }

        return null;
    }

    private String firstNonBlankText(
            JsonNode node,
            String... fieldNames
    ) {
        if (node == null || fieldNames == null) {
            return null;
        }

        for (String fieldName : fieldNames) {
            JsonNode value =
                    node.get(fieldName);

            if (value != null
                    && value.isTextual()
                    && !value.asText().isBlank()) {
                return value.asText();
            }
        }

        return null;
    }

    private long arraySize(JsonNode node) {
        return node != null
                && node.isArray()
                ? node.size()
                : 0L;
    }

    private void printSummary(
            String label,
            FactsExtractResponse response
    ) {
        System.out.println();
        System.out.println(
                "=== " + label + " 결과 요약 ==="
        );
        System.out.println(
                "runId:                "
                        + response.runId()
        );
        System.out.println(
                "mode:                 "
                        + response.mode()
        );
        System.out.println(
                "schemaVersion:        "
                        + response.schemaVersion()
        );
        System.out.println(
                "filesScanned:         "
                        + response.stats().filesScanned()
        );
        System.out.println(
                "types:                "
                        + response.stats().types()
        );
        System.out.println(
                "methods:              "
                        + response.stats().methods()
        );
        System.out.println(
                "fields:               "
                        + response.stats().fields()
        );
        System.out.println(
                "relations:            "
                        + response.stats().relations()
        );
        System.out.println(
                "evidence:             "
                        + response.stats().evidence()
        );
        System.out.println(
                "errors:               "
                        + response.stats().errors()
        );
        System.out.println(
                "warnings:             "
                        + response.warnings()
        );
        System.out.println();
    }
}