package com.example.ossdoc.domain.extraction;

import com.example.ossdoc.domain.artifact.entity.Artifact;
import com.example.ossdoc.domain.artifact.enums.ArtifactKind;
import com.example.ossdoc.domain.artifact.service.ArtifactService;
import com.example.ossdoc.domain.build.support.RepoRootResolver;
import com.example.ossdoc.domain.extraction.dto.request.FactsExtractRequest;
import com.example.ossdoc.domain.extraction.dto.response.FactsExtractResponse;
import com.example.ossdoc.domain.extraction.enums.BytecodeAvailability;
import com.example.ossdoc.domain.extraction.enums.ExtractionMode;
import com.example.ossdoc.domain.extraction.service.composer.DefaultFactsComposer;
import com.example.ossdoc.domain.extraction.service.extractor.AsmBytecodeFactsExtractor;
import com.example.ossdoc.domain.extraction.service.extractor.ChunkFactsExtractionCoordinator;
import com.example.ossdoc.domain.extraction.service.extractor.ExtractionContextFactory;
import com.example.ossdoc.domain.extraction.service.extractor.JavaParserAstFactsExtractor;
import com.example.ossdoc.domain.extraction.facade.DefaultFactsExtractionFacade;
import com.example.ossdoc.domain.extraction.service.support.preflight.ExtractionPreflightChecker;
import com.example.ossdoc.domain.extraction.service.support.preflight.BuildOutputVerifier;
import com.example.ossdoc.domain.extraction.service.support.preflight.BytecodeAvailabilityChecker;
import com.example.ossdoc.domain.extraction.service.support.preflight.ExtractionModeResolver;
import com.example.ossdoc.domain.extraction.service.support.preflight.BuildManifestLoader;
import com.example.ossdoc.domain.extraction.service.support.merge.ExtractionMergeSupport;
import com.example.ossdoc.domain.extraction.service.support.planning.ChunkPlanner;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Extraction 파이프라인 통합 테스트.
 *
 * <p>실제 워크스페이스(C:\data\ossdoc\run_20260323_798b4f45)를 사용하여
 * Phase 1~6(컨텍스트→Preflight→청크→추출→병합→구성)은 실제 로직으로 실행하고,
 * Phase 7(S3 업로드 + DB 저장)만 mock으로 우회한다.
 *
 * <p>DB/Security/S3 auto-configuration 없이 필요한 빈만 로드한다.
 */
@ExtendWith(SpringExtension.class)
@Import(ExtractionPipelineIntegrationTest.TestConfig.class)
class ExtractionPipelineIntegrationTest {

    private static final String TEST_RUN_ID = "run_20260323_798b4f45";

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
            WorkspaceManager.class,
    })
    static class TestConfig {

        @Bean
        public ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        public WorkspaceProperties workspaceProperties() {
            WorkspaceProperties props = new WorkspaceProperties();
            props.setBaseDir("C:/data/ossdoc");
            return props;
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

    @BeforeEach
    void setUp() {
        reset(repoRunRepository, artifactService);

        RepoRun mockRun = mock(RepoRun.class);
        when(mockRun.getRunId()).thenReturn(TEST_RUN_ID);
        when(mockRun.getRepoUrl()).thenReturn("https://github.com/ronmamo/reflections");
        when(mockRun.getCommitSha()).thenReturn("f5514b125c4f4b58e92beb0979a40ddce48d5be1");
        when(mockRun.getWorkspaceRoot()).thenReturn("C:/data/ossdoc/" + TEST_RUN_ID);

        // 첫 호출(facade.prepareFacadeContext) → empty → TEST_RUN_ID bypass 사용
        // 두 번째 호출(writer.uploadToS3) → mockRun 반환
        when(repoRunRepository.findById(TEST_RUN_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(mockRun));

        // ArtifactService: S3 업로드 우회
        when(artifactService.saveJsonArtifact(
                any(RepoRun.class),
                any(ArtifactKind.class),
                anyString(),
                anyString(),
                any(JsonNode.class)
        )).thenReturn(mock(Artifact.class));
    }

    @Test
    @DisplayName("자동 모드 — 실제 워크스페이스로 전체 파이프라인 성공")
    void extractFacts_autoMode_succeeds() {
        FactsExtractRequest request = FactsExtractRequest.builder()
                .runId(TEST_RUN_ID)
                .mode(null)
                .includeObservations(true)
                .includeTests(false)
                .failFast(false)
                .build();

        FactsExtractResponse response = factsExtractionFacade.extract(request);

        assertNotNull(response, "응답이 null이면 안 됨");
        assertEquals(TEST_RUN_ID, response.runId());
        assertNotNull(response.mode(), "추출 모드가 자동 결정되어야 함");
        assertNotNull(response.schemaVersion());
        assertNotNull(response.stats(), "stats가 null이면 안 됨");
        assertTrue(response.stats().filesScanned() > 0, "스캔된 파일이 0보다 커야 함");
        assertTrue(response.stats().types() > 0, "추출된 타입이 0보다 커야 함");
        assertTrue(response.stats().relations() > 0, "추출된 관계가 0보다 커야 함");

        printSummary("자동 모드", response);
    }

    @Test
    @DisplayName("AST_ONLY 모드 — bytecode 없이 소스만 추출")
    void extractFacts_astOnly_succeeds() {
        FactsExtractRequest request = FactsExtractRequest.builder()
                .runId(TEST_RUN_ID)
                .mode(ExtractionMode.AST_ONLY)
                .includeObservations(false)
                .includeTests(false)
                .failFast(false)
                .build();

        FactsExtractResponse response = factsExtractionFacade.extract(request);

        assertNotNull(response);
        assertEquals(TEST_RUN_ID, response.runId());
        assertEquals(ExtractionMode.AST_ONLY, response.mode());
        assertTrue(response.stats().filesScanned() > 0, "AST 스캔 파일이 0보다 커야 함");
        assertTrue(response.stats().types() > 0, "추출된 타입이 0보다 커야 함");

        printSummary("AST_ONLY", response);
    }

    @Test
    @DisplayName("AST_PLUS_BYTECODE 모드 — 소스 + 바이트코드 추출")
    void extractFacts_withBytecode_succeeds() {
        FactsExtractRequest request = FactsExtractRequest.builder()
                .runId(TEST_RUN_ID)
                .mode(ExtractionMode.AST_PLUS_BYTECODE)
                .includeObservations(true)
                .includeTests(false)
                .failFast(false)
                .build();

        FactsExtractResponse response = factsExtractionFacade.extract(request);

        assertNotNull(response);
        assertEquals(TEST_RUN_ID, response.runId());
        assertEquals(ExtractionMode.AST_PLUS_BYTECODE, response.mode());
        assertNotNull(response.bytecodeAvailability());
        assertNotEquals(BytecodeAvailability.NONE, response.bytecodeAvailability(),
                "바이트코드가 존재하므로 NONE이면 안 됨");
        assertTrue(response.stats().classFilesScanned() > 0,
                "바이트코드 모드에서 class 파일 스캔이 0보다 커야 함");

        printSummary("AST_PLUS_BYTECODE", response);
    }

    private void printSummary(String label, FactsExtractResponse response) {
        System.out.println();
        System.out.println("=== " + label + " 결과 요약 ===");
        System.out.println("runId:                " + response.runId());
        System.out.println("mode:                 " + response.mode());
        System.out.println("bytecodeAvailability: " + response.bytecodeAvailability());
        System.out.println("schemaVersion:        " + response.schemaVersion());
        System.out.println("scannedModules:       " + response.scannedModules());
        System.out.println("filesScanned:         " + response.stats().filesScanned());
        System.out.println("astFilesScanned:      " + response.stats().astFilesScanned());
        System.out.println("classFilesScanned:    " + response.stats().classFilesScanned());
        System.out.println("types:                " + response.stats().types());
        System.out.println("methods:              " + response.stats().methods());
        System.out.println("fields:               " + response.stats().fields());
        System.out.println("relations:            " + response.stats().relations());
        System.out.println("evidence:             " + response.stats().evidence());
        System.out.println("errors:               " + response.stats().errors());
        System.out.println("warnings:             " + response.warnings());
        System.out.println();
    }
}
