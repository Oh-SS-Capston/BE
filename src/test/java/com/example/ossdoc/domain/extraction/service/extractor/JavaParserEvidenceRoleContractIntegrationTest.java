package com.example.ossdoc.domain.extraction.service.extractor;

import com.example.ossdoc.domain.extraction.dto.context.ExtractionContext;
import com.example.ossdoc.domain.extraction.dto.context.ExtractionSink;
import com.example.ossdoc.domain.extraction.dto.model.EvidenceFact;
import com.example.ossdoc.domain.extraction.dto.model.ObservationFact;
import com.example.ossdoc.domain.extraction.dto.model.RelationFact;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.modules.ModuleDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JavaParserEvidenceRoleContractIntegrationTest {

    private static final Set<String> EXPECTED_ROLES = Set.of(
            "annotation",
            "method_call",
            "object_creation",
            "field_access",
            "endpoint_mapping",
            "bean_provider",
            "configuration_wiring",
            "injection_annotation",
            "injection_field",
            "constructor_parameter",
            "event_subscription",
            "event_payload_parameter",
            "event_publication",
            "reflection_call",
            "service_loader",
            "module_uses",
            "module_provides"
    );

    private final JavaParserAstFactsExtractor extractor =
            new JavaParserAstFactsExtractor();

    @Test
    @DisplayName("구조·DI·HTTP·Event·SPI·Reflection Evidence 역할이 한 추출 결과에 함께 유지된다")
    void allEvidenceRolesAreGeneratedAndReferenced() throws Exception {
        ExtractionSink sink = extractFixture();
        var facts = sink.toExtractedFacts();

        Set<String> actualRoles = facts.evidence()
                .values()
                .stream()
                .map(this::roleOf)
                .filter(role -> role != null && !role.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        assertTrue(
                actualRoles.containsAll(EXPECTED_ROLES),
                () -> "누락된 Evidence role: "
                        + difference(EXPECTED_ROLES, actualRoles)
                        + ", 실제 role: "
                        + actualRoles
        );

        Set<String> referencedEvidenceIds =
                referencedEvidenceIds(
                        facts.relations().calls(),
                        facts.relations().creates(),
                        facts.relations().overrides(),
                        facts.relations().accessesField(),
                        facts.relations().annotatedWith(),
                        facts.observations().diInjectionSites(),
                        facts.observations().diProviders(),
                        facts.observations().spiProviders(),
                        facts.observations().eventPublications(),
                        facts.observations().eventSubscriptions(),
                        facts.observations().reflectionSites(),
                        facts.observations().httpEndpoints(),
                        facts.observations().configWiring(),
                        facts.observations().moduleUses(),
                        facts.observations().moduleProvides()
                );

        for (String expectedRole : EXPECTED_ROLES) {
            List<EvidenceFact> roleEvidence =
                    evidenceWithRole(facts.evidence(), expectedRole);

            assertFalse(
                    roleEvidence.isEmpty(),
                    () -> expectedRole
                            + " Evidence가 생성되어야 함"
            );

            assertTrue(
                    roleEvidence.stream().anyMatch(evidence ->
                            referencedEvidenceIds.contains(evidence.id())
                    ),
                    () -> expectedRole
                            + " Evidence가 Relation 또는 Observation에서 참조되어야 함"
            );
        }
    }

    @Test
    @DisplayName("표현식·어노테이션 하나가 여러 사실의 근거일 때 역할별 Evidence ID가 충돌하지 않는다")
    void sameAstNodeHasDistinctEvidenceIdsByRole() throws Exception {
        Map<String, EvidenceFact> evidence =
                extractFixture().toExtractedFacts().evidence();

        assertDistinctRoleIds(
                evidence,
                "@Bean",
                "annotation",
                "bean_provider"
        );

        assertDistinctRoleIds(
                evidence,
                "@GetMapping(\"/users\")",
                "annotation",
                "endpoint_mapping"
        );

        assertDistinctRoleIds(
                evidence,
                "@EventListener",
                "annotation",
                "event_subscription"
        );

        assertDistinctRoleIds(
                evidence,
                "publisher.publishEvent(event)",
                "method_call",
                "event_publication"
        );

        assertDistinctRoleIds(
                evidence,
                "Class.forName(\"sample.UserService\")",
                "method_call",
                "reflection_call"
        );

        assertDistinctRoleIds(
                evidence,
                "ServiceLoader.load(Plugin.class)",
                "method_call",
                "service_loader"
        );
    }

    @Test
    @DisplayName("역할 Evidence는 정확한 snippet과 유효한 line·column 범위를 가진다")
    void evidenceSpansAndSnippetsFollowContract() throws Exception {
        Map<String, EvidenceFact> evidence =
                extractFixture().toExtractedFacts().evidence();

        assertRoleHasExactSnippet(
                evidence,
                "injection_field",
                "UserService userService"
        );
        assertRoleHasExactSnippet(
                evidence,
                "constructor_parameter",
                "AuditService auditService"
        );
        assertRoleHasExactSnippet(
                evidence,
                "event_payload_parameter",
                "OrderCreatedEvent event"
        );
        assertRoleHasExactSnippet(
                evidence,
                "event_publication",
                "publisher.publishEvent(event)"
        );
        assertRoleHasExactSnippet(
                evidence,
                "reflection_call",
                "Class.forName(\"sample.UserService\")"
        );
        assertRoleHasExactSnippet(
                evidence,
                "service_loader",
                "ServiceLoader.load(Plugin.class)"
        );
        assertRoleHasExactSnippet(
                evidence,
                "module_uses",
                "uses sample.spi.Plugin;"
        );

        assertTrue(
                evidenceWithRole(
                        evidence,
                        "module_provides"
                ).stream().anyMatch(item ->
                        item.snippet().startsWith(
                                "provides sample.spi.Plugin"
                        )
                                && item.snippet().contains(
                                "with sample.spi.DefaultPlugin;"
                        )
                )
        );

        for (EvidenceFact item : evidence.values()) {
            String role = roleOf(item);

            if (role == null || !EXPECTED_ROLES.contains(role)) {
                continue;
            }

            assertNotNull(
                    item.startLine(),
                    () -> role + " startLine 누락"
            );
            assertNotNull(
                    item.endLine(),
                    () -> role + " endLine 누락"
            );
            assertNotNull(
                    item.startCol(),
                    () -> role + " startCol 누락"
            );
            assertNotNull(
                    item.endCol(),
                    () -> role + " endCol 누락"
            );
            assertNotNull(
                    item.snippet(),
                    () -> role + " snippet 누락"
            );
            assertFalse(
                    item.snippet().isBlank(),
                    () -> role + " snippet이 비어 있음"
            );
            assertNotNull(
                    item.attrs().get("granularity"),
                    () -> role + " granularity 누락"
            );

            assertTrue(
                    item.startLine() < item.endLine()
                            || item.startCol() <= item.endCol(),
                    () -> role + " 범위가 역전됨: "
                            + item.startLine()
                            + ":"
                            + item.startCol()
                            + " - "
                            + item.endLine()
                            + ":"
                            + item.endCol()
            );
        }
    }

    private ExtractionSink extractFixture() throws Exception {
        ExtractionSink sink = new ExtractionSink();
        ExtractionContext context = observationContext();

        String source = """
                package sample;

                import java.util.ServiceLoader;

                @Configuration
                @ComponentScan("sample")
                @RequestMapping("/api")
                class EvidenceFixture {

                    @Autowired
                    private UserService userService;

                    private Publisher publisher;

                    @Autowired
                    EvidenceFixture(AuditService auditService) {
                    }

                    @Bean
                    @Primary
                    UserService userService() {
                        return new UserService();
                    }

                    @GetMapping("/users")
                    String endpoint() {
                        System.out.println(userService);
                        return userService.toString();
                    }

                    @EventListener
                    void handle(OrderCreatedEvent event) throws Exception {
                        publisher.publishEvent(event);
                        Class.forName("sample.UserService");
                        ServiceLoader.load(Plugin.class);
                    }
                }

                class UserService {
                }

                class AuditService {
                }

                class OrderCreatedEvent {
                }

                class Publisher {
                    void publishEvent(Object event) {
                    }
                }

                interface Plugin {
                }
                """;

        CompilationUnit unit = parser()
                .parse(source)
                .getResult()
                .orElseThrow();

        for (TypeDeclaration<?> type : unit.getTypes()) {
            invoke(
                    "collectTypeRecursive",
                    new Class<?>[]{
                            ExtractionContext.class,
                            String.class,
                            String.class,
                            String.class,
                            TypeDeclaration.class,
                            List.class,
                            ExtractionSink.class
                    },
                    context,
                    "src/main/java/sample/EvidenceFixture.java",
                    "package:sample",
                    null,
                    type,
                    source.lines().toList(),
                    sink
            );
        }

        String moduleSource = """
                module sample.app {
                    uses sample.spi.Plugin;
                    provides sample.spi.Plugin
                            with sample.spi.DefaultPlugin;
                }
                """;

        CompilationUnit moduleUnit = parser()
                .parse(moduleSource)
                .getResult()
                .orElseThrow();

        ModuleDeclaration module =
                moduleUnit.getModule().orElseThrow();

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
                moduleSource.lines().toList(),
                sink
        );

        return sink;
    }

    private JavaParser parser() {
        ParserConfiguration configuration =
                new ParserConfiguration()
                        .setSymbolResolver(
                                new JavaSymbolSolver(
                                        new ReflectionTypeSolver(false)
                                )
                        );

        return new JavaParser(configuration);
    }

    private ExtractionContext observationContext() {
        ExtractionContext context =
                mock(ExtractionContext.class);

        when(context.includeObservations()).thenReturn(true);
        when(context.module()).thenReturn("sample-app");
        when(context.sourceRootString()).thenReturn(
                "src/main/java"
        );

        return context;
    }
    @SafeVarargs
    private final Set<String> referencedEvidenceIds(
            List<?>... factGroups
    ) {
        LinkedHashSet<String> result =
                new LinkedHashSet<>();

        for (List<?> factGroup : factGroups) {
            if (factGroup == null) {
                continue;
            }

            for (Object fact : factGroup) {
                if (fact instanceof RelationFact relation
                        && relation.evidenceIds() != null) {
                    result.addAll(relation.evidenceIds());
                } else if (fact instanceof ObservationFact observation
                        && observation.evidenceIds() != null) {
                    result.addAll(observation.evidenceIds());
                }
            }
        }

        return Set.copyOf(result);
    }

    private void assertDistinctRoleIds(
            Map<String, EvidenceFact> evidence,
            String snippet,
            String firstRole,
            String secondRole
    ) {
        EvidenceFact first = findEvidence(
                evidence,
                item -> snippet.equals(item.snippet())
                        && firstRole.equals(roleOf(item))
        );

        EvidenceFact second = findEvidence(
                evidence,
                item -> snippet.equals(item.snippet())
                        && secondRole.equals(roleOf(item))
        );

        assertNotEquals(
                first.id(),
                second.id(),
                () -> snippet
                        + "의 "
                        + firstRole
                        + "와 "
                        + secondRole
                        + " Evidence ID가 달라야 함"
        );

        assertEquals(first.startLine(), second.startLine());
        assertEquals(first.startCol(), second.startCol());
        assertEquals(first.endLine(), second.endLine());
        assertEquals(first.endCol(), second.endCol());
    }

    private void assertRoleHasExactSnippet(
            Map<String, EvidenceFact> evidence,
            String role,
            String expectedSnippet
    ) {
        assertTrue(
                evidenceWithRole(evidence, role)
                        .stream()
                        .anyMatch(item ->
                                expectedSnippet.equals(
                                        item.snippet()
                                )
                        ),
                () -> role
                        + "에 기대 snippet이 없음: "
                        + expectedSnippet
        );
    }

    private EvidenceFact findEvidence(
            Map<String, EvidenceFact> evidence,
            Predicate<EvidenceFact> predicate
    ) {
        return evidence.values()
                .stream()
                .filter(predicate)
                .findFirst()
                .orElseThrow();
    }

    private List<EvidenceFact> evidenceWithRole(
            Map<String, EvidenceFact> evidence,
            String role
    ) {
        return evidence.values()
                .stream()
                .filter(item ->
                        role.equals(roleOf(item))
                )
                .toList();
    }

    private String roleOf(EvidenceFact evidence) {
        if (evidence == null || evidence.attrs() == null) {
            return null;
        }

        Object role = evidence.attrs().get("role");
        return role == null ? null : String.valueOf(role);
    }

    private Set<String> difference(
            Set<String> expected,
            Set<String> actual
    ) {
        LinkedHashSet<String> difference =
                new LinkedHashSet<>(expected);
        difference.removeAll(actual);
        return Set.copyOf(difference);
    }

    private Object invoke(
            String methodName,
            Class<?>[] parameterTypes,
            Object... arguments
    ) throws Exception {
        Method method = JavaParserAstFactsExtractor.class
                .getDeclaredMethod(
                        methodName,
                        parameterTypes
                );

        method.setAccessible(true);
        return method.invoke(extractor, arguments);
    }
}
