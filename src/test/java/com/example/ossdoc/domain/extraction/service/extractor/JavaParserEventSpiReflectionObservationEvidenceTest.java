package com.example.ossdoc.domain.extraction.service.extractor;

import com.example.ossdoc.domain.extraction.dto.context.ExtractionContext;
import com.example.ossdoc.domain.extraction.dto.context.ExtractionSink;
import com.example.ossdoc.domain.extraction.dto.model.EvidenceFact;
import com.example.ossdoc.domain.extraction.dto.model.ObservationFact;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.modules.ModuleDeclaration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JavaParserEventSpiReflectionObservationEvidenceTest {

    private final JavaParserAstFactsExtractor extractor =
            new JavaParserAstFactsExtractor();

    @Test
    @DisplayName("Event·ServiceLoader·Reflection Observation이 정확한 코드 범위를 참조한다")
    void methodObservationsUseExactEvidenceRanges() throws Exception {
        String source = """
                class EventBridge {
                    @EventListener
                    void handle(OrderCreatedEvent event) {
                        publisher.publishEvent(event);
                        Class.forName("sample.UserService");
                        ServiceLoader.load(Plugin.class);
                    }
                }
                """;

        CompilationUnit unit = StaticJavaParser.parse(source);
        TypeDeclaration<?> ownerType = unit.getType(0);
        MethodDeclaration method = ownerType
                .getMethodsByName("handle")
                .get(0);
        ExtractionSink sink = new ExtractionSink();

        invoke(
                "addMethodObservationsIfNeeded",
                new Class<?>[]{
                        ExtractionContext.class,
                        TypeDeclaration.class,
                        MethodDeclaration.class,
                        String.class,
                        String.class,
                        List.class,
                        String.class,
                        ExtractionSink.class
                },
                observationContext(),
                ownerType,
                method,
                "method:sample.EventBridge#handle(OrderCreatedEvent)",
                "src/main/java/sample/EventBridge.java",
                source.lines().toList(),
                "method-evidence",
                sink
        );

        var observations = sink.toExtractedFacts().observations();

        ObservationFact subscription =
                observations.eventSubscriptions().get(0);
        Map<String, EvidenceFact> subscriptionByRole =
                evidenceOf(sink, subscription.evidenceIds())
                        .stream()
                        .collect(Collectors.toMap(
                                item -> String.valueOf(
                                        item.attrs().get("role")
                                ),
                                item -> item
                        ));

        assertEquals(
                "@EventListener",
                subscriptionByRole
                        .get("event_subscription")
                        .snippet()
        );
        assertEquals(
                "OrderCreatedEvent event",
                subscriptionByRole
                        .get("event_payload_parameter")
                        .snippet()
        );
        assertEquals(
                "parameter",
                subscriptionByRole
                        .get("event_payload_parameter")
                        .attrs()
                        .get("granularity")
        );

        ObservationFact publication =
                observations.eventPublications().get(0);
        EvidenceFact publicationEvidence =
                singleEvidence(sink, publication);

        assertEquals(
                "publisher.publishEvent(event)",
                publicationEvidence.snippet()
        );
        assertRole(
                publicationEvidence,
                "expression",
                "event_publication"
        );

        ObservationFact reflection =
                observations.reflectionSites()
                        .stream()
                        .filter(observation ->
                                "forName".equals(
                                        observation.attrs()
                                                .get("api_method")
                                )
                        )
                        .findFirst()
                        .orElseThrow();
        EvidenceFact reflectionEvidence =
                singleEvidence(sink, reflection);

        assertEquals(
                "Class.forName(\"sample.UserService\")",
                reflectionEvidence.snippet()
        );
        assertRole(
                reflectionEvidence,
                "expression",
                "reflection_call"
        );

        ObservationFact serviceLoader =
                observations.spiProviders().get(0);
        EvidenceFact serviceLoaderEvidence =
                singleEvidence(sink, serviceLoader);

        assertEquals(
                "ServiceLoader.load(Plugin.class)",
                serviceLoaderEvidence.snippet()
        );
        assertRole(
                serviceLoaderEvidence,
                "expression",
                "service_loader"
        );

        assertFalse(
                publicationEvidence.snippet()
                        .contains("Class.forName")
        );
        assertFalse(
                reflectionEvidence.snippet()
                        .contains("ServiceLoader.load")
        );
    }

    @Test
    @DisplayName("module uses·provides Observation이 각 지시문 Evidence를 참조한다")
    void moduleObservationsUseDirectiveEvidence() throws Exception {
        String source = """
                module sample.app {
                    uses sample.spi.Plugin;
                    provides sample.spi.Plugin
                            with sample.spi.DefaultPlugin;
                }
                """;

        CompilationUnit unit = StaticJavaParser.parse(source);
        ModuleDeclaration module = unit.getModule().orElseThrow();
        ExtractionSink sink = new ExtractionSink();

        invoke(
                "collectModuleDirectives",
                new Class<?>[]{
                        ModuleDeclaration.class,
                        String.class,
                        List.class,
                        ExtractionSink.class
                },
                module,
                "src/main/java/module-info.java",
                source.lines().toList(),
                sink
        );

        var observations = sink.toExtractedFacts().observations();

        ObservationFact uses =
                observations.moduleUses().get(0);
        EvidenceFact usesEvidence =
                singleEvidence(sink, uses);

        assertEquals(
                "uses sample.spi.Plugin;",
                usesEvidence.snippet()
        );
        assertRole(
                usesEvidence,
                "module_directive",
                "module_uses"
        );

        ObservationFact provides =
                observations.moduleProvides().get(0);
        EvidenceFact providesEvidence =
                singleEvidence(sink, provides);

        assertTrue(
                providesEvidence.snippet().startsWith(
                        "provides sample.spi.Plugin"
                )
        );
        assertTrue(
                providesEvidence.snippet().contains(
                        "with sample.spi.DefaultPlugin;"
                )
        );
        assertRole(
                providesEvidence,
                "module_directive",
                "module_provides"
        );

        assertNotNull(
                provides.attrs().get("implementation")
        );
    }

    private ExtractionContext observationContext() {
        ExtractionContext context =
                mock(ExtractionContext.class);
        when(context.includeObservations()).thenReturn(true);
        return context;
    }

    private EvidenceFact singleEvidence(
            ExtractionSink sink,
            ObservationFact observation
    ) {
        List<EvidenceFact> evidence =
                evidenceOf(sink, observation.evidenceIds());

        assertEquals(1, evidence.size());
        return evidence.get(0);
    }

    private List<EvidenceFact> evidenceOf(
            ExtractionSink sink,
            List<String> evidenceIds
    ) {
        Map<String, EvidenceFact> evidence =
                sink.toExtractedFacts().evidence();

        return evidenceIds.stream()
                .map(evidence::get)
                .toList();
    }

    private void assertRole(
            EvidenceFact evidence,
            String granularity,
            String role
    ) {
        assertEquals(
                granularity,
                evidence.attrs().get("granularity")
        );
        assertEquals(
                role,
                evidence.attrs().get("role")
        );
    }

    private Object invoke(
            String methodName,
            Class<?>[] parameterTypes,
            Object... arguments
    ) throws Exception {
        Method method = JavaParserAstFactsExtractor.class
                .getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        return method.invoke(extractor, arguments);
    }
}
