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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;
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

@ExtendWith(SpringExtension.class)
@Import(AstBeanConfigurationObservationIntegrationTest.TestConfig.class)
class AstBeanConfigurationObservationIntegrationTest {

    private static final String TEST_RUN_ID =
            "run_ast_bean_configuration_test";

    @Configuration
    @Import({
            DefaultFactsExtractionFacade.class,
            ExtractionPreflightChecker.class,
            BuildOutputVerifier.class,
            BytecodeAvailabilityChecker.class,
            ExtractionModeResolver.class,
            ChunkPlanner.class,
            ChunkFactsExtractionCoordinator.class,
            ExtractionContextFactory.class,
            JavaParserAstFactsExtractor.class,
            AsmBytecodeFactsExtractor.class,
            MetaInfServiceScanner.class,
            ReadmeObservationScanner.class,
            ExtractionMergeSupport.class,
            DefaultFactsComposer.class,
            DefaultFactsWriter.class,
            FactsResponseFactory.class,
            BuildManifestLoader.class,
            ExtractionClock.class,
            RepoRootResolver.class,
            WorkspaceManager.class
    })
    static class TestConfig {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper()
                    .findAndRegisterModules();
        }

        @Bean
        WorkspaceProperties workspaceProperties() {
            WorkspaceProperties properties =
                    new WorkspaceProperties();

            properties.setBaseDir(
                    Path.of(
                            System.getProperty(
                                    "java.io.tmpdir"
                            ),
                            "ossdoc-tests"
                    ).toString()
            );

            return properties;
        }

        @Bean
        RepoRunRepository repoRunRepository() {
            return mock(RepoRunRepository.class);
        }

        @Bean
        ArtifactService artifactService() {
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

    private Path workspaceRoot;

    @BeforeEach
    void setUp() throws IOException {
        reset(repoRunRepository, artifactService);

        workspaceRoot = createWorkspace();

        RepoRun run = mock(RepoRun.class);

        when(run.getRunId())
                .thenReturn(TEST_RUN_ID);

        when(run.getRepoUrl())
                .thenReturn(
                        "https://github.com/example/bean-config"
                );

        when(run.getCommitSha())
                .thenReturn("bean-config-commit");

        when(run.getWorkspaceRoot())
                .thenReturn(workspaceRoot.toString());

        when(repoRunRepository.findById(TEST_RUN_ID))
                .thenReturn(Optional.of(run));

        when(artifactService.saveJsonArtifact(
                any(RepoRun.class),
                any(ArtifactKind.class),
                anyString(),
                anyString(),
                any(JsonNode.class)
        )).thenReturn(mock(Artifact.class));
    }

    @Test
    @DisplayName("AST_ONLY — Bean Provider와 Configuration Wiring 속성 추출")
    void extractsBeanProvidersAndConfigurationWiring() {
        FactsExtractRequest request =
                FactsExtractRequest.builder()
                        .runId(TEST_RUN_ID)
                        .mode(ExtractionMode.AST_ONLY)
                        .includeObservations(true)
                        .includeTests(false)
                        .failFast(false)
                        .build();

        FactsExtractResponse response =
                factsExtractionFacade.extract(request);

        assertNotNull(response);
        assertEquals("ast_only", response.mode());

        JsonNode factsJson = captureFactsJson();

        JsonNode providers =
                factsJson.path("observations")
                        .path("di_providers");

        JsonNode configWiring =
                factsJson.path("observations")
                        .path("config_wiring");

        assertTrue(
                providers.isArray(),
                "observations.di_providers는 배열이어야 함"
        );

        assertEquals(
                3,
                providers.size(),
                "서비스 타입, @Bean 메서드, @Produces 메서드가 추출되어야 함"
        );

        assertTrue(
                configWiring.isArray(),
                "observations.config_wiring은 배열이어야 함"
        );

        assertEquals(
                1,
                configWiring.size(),
                "Configuration wiring observation은 한 건이어야 함"
        );

        JsonNode objectMapperProvider =
                findObservationBySite(
                        providers,
                        "objectMapper"
                );

        assertNotNull(objectMapperProvider);

        assertEquals(
                "ast",
                objectMapperProvider.path("origin").asText()
        );

        assertEquals(
                "bean_method",
                objectMapperProvider.path("attrs")
                        .path("provider_kind")
                        .asText()
        );

        assertEquals(
                Set.of("apiMapper", "objectMapper"),
                textValues(
                        objectMapperProvider.path("attrs")
                                .path("bean_names")
                )
        );

        assertTrue(
                objectMapperProvider.path("attrs")
                        .path("primary")
                        .asBoolean(),
                "@Primary가 반영되어야 함"
        );

        assertEquals(
                Set.of("api"),
                textValues(
                        objectMapperProvider.path("attrs")
                                .path("qualifiers")
                )
        );

        assertTrue(
                objectMapperProvider.path("attrs")
                        .path("owner_config_symbol")
                        .asText()
                        .endsWith("BeanConfigSample"),
                "소유 Configuration symbol이 기록되어야 함"
        );

        assertTrue(
                objectMapperProvider.path("attrs")
                        .path("owner_is_configuration")
                        .asBoolean(),
                "소유 타입이 Configuration임을 기록해야 함"
        );

        assertTrue(
                objectMapperProvider.path("target_type_ref")
                        .path("raw")
                        .asText()
                        .endsWith("ObjectMapper"),
                "Bean 반환 타입이 target_type_ref로 기록되어야 함"
        );

        assertAnnotationEvidence(
                factsJson.path("evidence"),
                objectMapperProvider,
                Set.of("@Bean", "@Primary", "@Qualifier")
        );

        JsonNode producerProvider =
                findObservationBySite(
                        providers,
                        "clock"
                );

        assertNotNull(producerProvider);

        assertEquals(
                "producer_method",
                producerProvider.path("attrs")
                        .path("provider_kind")
                        .asText()
        );

        assertEquals(
                Set.of("clock"),
                textValues(
                        producerProvider.path("attrs")
                                .path("bean_names")
                )
        );

        assertEquals(
                Set.of("clock"),
                textValues(
                        producerProvider.path("attrs")
                                .path("qualifiers")
                )
        );

        JsonNode serviceProvider =
                findObservationBySite(
                        providers,
                        "UserService"
                );

        assertNotNull(serviceProvider);

        assertEquals(
                "service_type",
                serviceProvider.path("attrs")
                        .path("provider_kind")
                        .asText()
        );

        assertEquals(
                Set.of("userService"),
                textValues(
                        serviceProvider.path("attrs")
                                .path("bean_names")
                )
        );

        assertTrue(
                serviceProvider.path("attrs")
                        .path("primary")
                        .asBoolean()
        );

        JsonNode wiring = configWiring.get(0);

        assertEquals(
                "ast",
                wiring.path("origin").asText()
        );

        assertEquals(
                "spring_configuration",
                wiring.path("attrs")
                        .path("configuration_kind")
                        .asText()
        );

        assertEquals(
                Set.of(
                        "spring_configuration",
                        "spring_import",
                        "spring_component_scan"
                ),
                textValues(
                        wiring.path("attrs")
                                .path("configuration_kinds")
                )
        );

        assertArrayContainsSuffix(
                wiring.path("attrs")
                        .path("imported_types"),
                "SecurityConfig"
        );

        assertArrayContainsSuffix(
                wiring.path("attrs")
                        .path("imported_types"),
                "PersistenceConfig"
        );

        assertEquals(
                Set.of("sample.api", "sample.service", "sample"),
                textValues(
                        wiring.path("attrs")
                                .path("component_scan_packages")
                )
        );

        assertArrayContainsSuffix(
                wiring.path("attrs")
                        .path(
                                "component_scan_base_package_classes"
                        ),
                "FeatureMarker"
        );

        assertAnnotationEvidence(
                factsJson.path("evidence"),
                wiring,
                Set.of(
                        "@Configuration",
                        "@Import",
                        "@ComponentScan"
                )
        );
    }

    private Path createWorkspace() throws IOException {
        Path root = tempDir.resolve(TEST_RUN_ID);
        Path repoRoot = root.resolve("repo");
        Path packageDirectory =
                repoRoot.resolve("src/main/java/sample");
        Path classesRoot =
                repoRoot.resolve("build/classes/java/main");
        Path artifactsRoot = root.resolve("artifacts");

        Files.createDirectories(packageDirectory);
        Files.createDirectories(classesRoot);
        Files.createDirectories(artifactsRoot);

        Files.writeString(
                repoRoot.resolve("settings.gradle"),
                "rootProject.name = 'bean-config'\n",
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

        Path sourceFile =
                writeSource(packageDirectory);

        compileSource(sourceFile, classesRoot);
        writeManifest(artifactsRoot);

        return root;
    }

    private Path writeSource(
            Path packageDirectory
    ) throws IOException {
        Path sourceFile =
                packageDirectory.resolve(
                        "BeanConfigSample.java"
                );

        Files.writeString(
                sourceFile,
                """
                package sample;

                @Configuration
                @Import({
                        SecurityConfig.class,
                        PersistenceConfig.class
                })
                @ComponentScan(
                        basePackages = {
                                "sample.api",
                                "sample.service"
                        },
                        basePackageClasses = {
                                FeatureMarker.class
                        }
                )
                public class BeanConfigSample {

                    @Bean(
                            name = {
                                    "apiMapper",
                                    "objectMapper"
                            }
                    )
                    @Primary
                    @Qualifier("api")
                    public ObjectMapper objectMapper() {
                        return new ObjectMapper();
                    }

                    @Produces
                    @Named("clock")
                    public Clock clock() {
                        return new Clock();
                    }
                }

                @Service("userService")
                @Primary
                class UserService {
                }

                class SecurityConfig {
                }

                class PersistenceConfig {
                }

                class FeatureMarker {
                }

                class ObjectMapper {
                }

                class Clock {
                }

                @interface Configuration {
                }

                @interface Import {
                    Class<?>[] value();
                }

                @interface ComponentScan {
                    String[] value() default {};

                    String[] basePackages() default {};

                    Class<?>[] basePackageClasses()
                            default {};
                }

                @interface Bean {
                    String[] value() default {};

                    String[] name() default {};
                }

                @interface Primary {
                }

                @interface Qualifier {
                    String value();
                }

                @interface Produces {
                }

                @interface Named {
                    String value() default "";
                }

                @interface Service {
                    String value() default "";
                }
                """,
                StandardCharsets.UTF_8
        );

        return sourceFile;
    }

    private void compileSource(
            Path sourceFile,
            Path classesRoot
    ) {
        JavaCompiler compiler =
                ToolProvider.getSystemJavaCompiler();

        assertNotNull(
                compiler,
                "통합 테스트는 JDK에서 실행되어야 함"
        );

        int exitCode = compiler.run(
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
                "테스트용 Bean Configuration 소스 컴파일 실패"
        );
    }

    private void writeManifest(
            Path artifactsRoot
    ) throws IOException {
        BuildModuleManifest module =
                BuildModuleManifest.builder()
                        .moduleId(":")
                        .name("bean-config")
                        .groupId("sample")
                        .artifactId("bean-config")
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

        BuildManifest manifest =
                BuildManifest.builder()
                        .runId(TEST_RUN_ID)
                        .detectedAt(OffsetDateTime.now())
                        .buildTool(BuildToolKind.GRADLE)
                        .wrapperUsed(false)
                        .buildMode(BuildMode.FULL)
                        .modules(List.of(module))
                        .failures(List.of())
                        .build();

        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(
                        artifactsRoot.resolve(
                                "build_manifest.json"
                        ).toFile(),
                        manifest
                );
    }

    private JsonNode captureFactsJson() {
        ArgumentCaptor<JsonNode> captor =
                ArgumentCaptor.forClass(JsonNode.class);

        verify(artifactService)
                .saveJsonArtifact(
                        any(RepoRun.class),
                        eq(ArtifactKind.FACTS_JSON),
                        anyString(),
                        anyString(),
                        captor.capture()
                );

        return captor.getValue();
    }

    private JsonNode findObservationBySite(
            JsonNode observations,
            String siteFragment
    ) {
        if (observations == null
                || !observations.isArray()) {
            return null;
        }

        for (JsonNode observation : observations) {
            String siteSymbol =
                    observation.path("site_symbol")
                            .asText("");

            if (siteSymbol.contains(siteFragment)) {
                return observation;
            }
        }

        return null;
    }

    private Set<String> textValues(
            JsonNode arrayNode
    ) {
        Set<String> result = new HashSet<>();

        if (arrayNode == null
                || !arrayNode.isArray()) {
            return result;
        }

        for (JsonNode value : arrayNode) {
            if (value.isTextual()) {
                result.add(value.asText());
            }
        }

        return result;
    }

    private void assertArrayContainsSuffix(
            JsonNode arrayNode,
            String suffix
    ) {
        assertTrue(
                arrayNode != null
                        && arrayNode.isArray()
                        && containsSuffix(
                        arrayNode,
                        suffix
                ),
                "배열에 " + suffix
                        + " 타입이 포함되어야 함"
        );
    }

    private boolean containsSuffix(
            JsonNode arrayNode,
            String suffix
    ) {
        for (JsonNode value : arrayNode) {
            if (value.isTextual()
                    && value.asText().endsWith(
                    suffix
            )) {
                return true;
            }
        }

        return false;
    }

    private void assertAnnotationEvidence(
            JsonNode evidenceSection,
            JsonNode observation,
            Set<String> expectedPrefixes
    ) {
        JsonNode evidenceIds =
                observation.path("evidence_ids");

        assertTrue(
                evidenceIds.isArray(),
                "observation evidence_ids는 배열이어야 함"
        );

        Set<String> snippets = new HashSet<>();

        for (JsonNode evidenceId : evidenceIds) {
            JsonNode evidence =
                    findEvidence(
                            evidenceSection,
                            evidenceId.asText()
                    );

            assertNotNull(
                    evidence,
                    "observation이 참조하는 Evidence가 존재해야 함"
            );

            snippets.add(
                    evidence.path("snippet")
                            .asText("")
            );
        }

        for (String expectedPrefix
                : expectedPrefixes) {
            assertTrue(
                    snippets.stream()
                            .map(String::stripLeading)
                            .anyMatch(snippet ->
                                    snippet.startsWith(
                                            expectedPrefix
                                    )
                            ),
                    expectedPrefix
                            + " annotation Evidence가 있어야 함"
            );
        }
    }

    private JsonNode findEvidence(
            JsonNode evidenceSection,
            String evidenceId
    ) {
        if (evidenceSection.isObject()) {
            return evidenceSection.get(evidenceId);
        }

        if (evidenceSection.isArray()) {
            for (JsonNode evidence : evidenceSection) {
                if (evidenceId.equals(
                        evidence.path("id")
                                .asText()
                )) {
                    return evidence;
                }
            }
        }

        return null;
    }
}
