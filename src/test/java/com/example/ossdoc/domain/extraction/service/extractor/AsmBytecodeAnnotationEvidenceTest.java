package com.example.ossdoc.domain.extraction.service.extractor;

import com.example.ossdoc.domain.extraction.dto.context.ExtractionContext;
import com.example.ossdoc.domain.extraction.dto.context.ExtractionSink;
import com.example.ossdoc.domain.extraction.dto.model.EvidenceFact;
import com.example.ossdoc.domain.extraction.dto.model.ObservationFact;
import com.example.ossdoc.domain.extraction.dto.model.RelationFact;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AsmBytecodeAnnotationEvidenceTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("ANNOTATED_WITH와 Bean·DI·Configuration·Event Observation이 annotation Evidence를 참조한다")
    void annotationRelationsAndObservationsUseRoleEvidence()
            throws Exception {
        Path classes = compileFixture();
        Path classFile = classes.resolve(
                "sample/AnnotationFixture.class"
        );

        ExtractionSink sink = extract(
                classes,
                classFile
        );

        var facts = sink.toExtractedFacts();
        Map<String, EvidenceFact> evidence =
                facts.evidence();

        List<RelationFact> annotationRelations =
                facts.relations().annotatedWith();

        assertFalse(annotationRelations.isEmpty());

        for (RelationFact relation
                : annotationRelations) {
            EvidenceFact item = evidence.get(
                    relation.evidenceIds().get(0)
            );

            assertAnnotationEvidence(
                    item,
                    "annotation"
            );
        }

        ObservationFact typeProvider =
                facts.observations().diProviders()
                        .stream()
                        .filter(observation ->
                                observation.siteSymbol()
                                        .startsWith(
                                                "type:sample.AnnotationFixture"
                                        )
                        )
                        .findFirst()
                        .orElseThrow();

        assertObservationRoles(
                evidence,
                typeProvider,
                Set.of("bean_provider")
        );

        ObservationFact configuration =
                facts.observations().configWiring()
                        .get(0);

        assertObservationRoles(
                evidence,
                configuration,
                Set.of("configuration_wiring")
        );

        ObservationFact fieldInjection =
                facts.observations().diInjectionSites()
                        .get(0);

        assertObservationRoles(
                evidence,
                fieldInjection,
                Set.of("injection_annotation")
        );

        ObservationFact methodProvider =
                facts.observations().diProviders()
                        .stream()
                        .filter(observation ->
                                observation.siteSymbol()
                                        .startsWith(
                                                "method:sample.AnnotationFixture#service"
                                        )
                        )
                        .findFirst()
                        .orElseThrow();

        assertObservationRoles(
                evidence,
                methodProvider,
                Set.of("bean_provider")
        );

        ObservationFact eventSubscription =
                facts.observations().eventSubscriptions()
                        .get(0);

        assertObservationRoles(
                evidence,
                eventSubscription,
                Set.of("event_subscription")
        );

        assertRoleIdentitySeparated(
                evidence,
                "sample.Component",
                "annotation",
                "bean_provider"
        );
        assertRoleIdentitySeparated(
                evidence,
                "sample.Configuration",
                "annotation",
                "configuration_wiring"
        );
        assertRoleIdentitySeparated(
                evidence,
                "sample.Autowired",
                "annotation",
                "injection_annotation"
        );
        assertRoleIdentitySeparated(
                evidence,
                "sample.Bean",
                "annotation",
                "bean_provider"
        );
        assertRoleIdentitySeparated(
                evidence,
                "sample.EventListener",
                "annotation",
                "event_subscription"
        );
    }

    private void assertObservationRoles(
            Map<String, EvidenceFact> evidence,
            ObservationFact observation,
            Set<String> expectedRoles
    ) {
        Set<String> actualRoles =
                observation.evidenceIds()
                        .stream()
                        .map(evidence::get)
                        .map(this::roleOf)
                        .collect(Collectors.toSet());

        assertTrue(
                actualRoles.containsAll(expectedRoles),
                () -> "기대 role="
                        + expectedRoles
                        + ", 실제 role="
                        + actualRoles
        );

        observation.evidenceIds()
                .stream()
                .map(evidence::get)
                .forEach(item ->
                        assertAnnotationEvidence(
                                item,
                                roleOf(item)
                        )
                );
    }

    private void assertAnnotationEvidence(
            EvidenceFact evidence,
            String expectedRole
    ) {
        assertNotNull(evidence);
        assertEquals(
                "annotation",
                evidence.attrs().get("granularity")
        );
        assertEquals(
                expectedRole,
                evidence.attrs().get("role")
        );
        assertNotNull(
                evidence.attrs().get("annotation_name")
        );
        assertNotNull(
                evidence.attrs().get("descriptor")
        );
        assertNotNull(
                evidence.attrs().get("annotation_index")
        );
        assertTrue(evidence.snippet().startsWith("@"));
        assertNotNull(evidence.hash());
    }

    private void assertRoleIdentitySeparated(
            Map<String, EvidenceFact> evidence,
            String annotationName,
            String firstRole,
            String secondRole
    ) {
        EvidenceFact first =
                findAnnotationEvidence(
                        evidence,
                        annotationName,
                        firstRole
                );

        EvidenceFact second =
                findAnnotationEvidence(
                        evidence,
                        annotationName,
                        secondRole
                );

        assertNotEquals(
                first.id(),
                second.id()
        );
        assertEquals(
                first.attrs().get("annotation_index"),
                second.attrs().get("annotation_index")
        );
        assertEquals(first.symbol(), second.symbol());
    }

    private EvidenceFact findAnnotationEvidence(
            Map<String, EvidenceFact> evidence,
            String annotationName,
            String role
    ) {
        return evidence.values()
                .stream()
                .filter(item ->
                        annotationName.equals(
                                item.attrs().get(
                                        "annotation_name"
                                )
                        )
                )
                .filter(item ->
                        role.equals(roleOf(item))
                )
                .findFirst()
                .orElseThrow();
    }

    private String roleOf(EvidenceFact evidence) {
        return String.valueOf(
                evidence.attrs().get("role")
        );
    }

    private ExtractionSink extract(
            Path classes,
            Path classFile
    ) throws Exception {
        ExtractionContext context =
                mock(ExtractionContext.class);

        when(context.repoRoot()).thenReturn(tempDir);
        when(context.module()).thenReturn("sample-app");
        when(context.bytecodeRootString()).thenReturn(
                classes.toString()
        );
        when(context.includeObservations()).thenReturn(true);

        ExtractionSink sink = new ExtractionSink();

        Method visitClassFile =
                AsmBytecodeFactsExtractor.class
                        .getDeclaredMethod(
                                "visitClassFile",
                                ExtractionContext.class,
                                Path.class,
                                ExtractionSink.class
                        );

        visitClassFile.setAccessible(true);
        visitClassFile.invoke(
                new AsmBytecodeFactsExtractor(),
                context,
                classFile,
                sink
        );

        return sink;
    }

    private Path compileFixture() throws Exception {
        Path sourceRoot = tempDir.resolve("src");
        Path classes = tempDir.resolve("classes");
        Path sourceFile = sourceRoot.resolve(
                "sample/AnnotationFixture.java"
        );

        Files.createDirectories(
                sourceFile.getParent()
        );
        Files.createDirectories(classes);

        String source = """
                package sample;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.TYPE)
                @interface Component {
                    String value() default "";
                }

                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.TYPE)
                @interface Configuration {
                }

                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.FIELD)
                @interface Autowired {
                }

                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.METHOD)
                @interface Bean {
                    String[] name() default {};
                }

                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.METHOD)
                @interface EventListener {
                }

                @Component("fixtureBean")
                @Configuration
                public class AnnotationFixture {

                    @Autowired
                    private Service service;

                    @Bean(name = {"service", "serviceAlias"})
                    Service service() {
                        return new Service();
                    }

                    @EventListener
                    void handle(Event event) {
                    }
                }

                class Service {
                }

                class Event {
                }
                """;

        Files.writeString(
                sourceFile,
                source,
                StandardCharsets.UTF_8
        );

        JavaCompiler compiler =
                ToolProvider.getSystemJavaCompiler();

        assertNotNull(
                compiler,
                "JDK JavaCompiler가 필요함"
        );

        int exitCode = compiler.run(
                null,
                null,
                null,
                "-g",
                "-parameters",
                "-encoding",
                "UTF-8",
                "-d",
                classes.toString(),
                sourceFile.toString()
        );

        assertEquals(
                0,
                exitCode,
                "fixture 컴파일 실패"
        );

        return classes;
    }
}
