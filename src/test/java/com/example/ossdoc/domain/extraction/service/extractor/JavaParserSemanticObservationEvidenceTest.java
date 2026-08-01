package com.example.ossdoc.domain.extraction.service.extractor;

import com.example.ossdoc.domain.extraction.dto.context.ExtractionContext;
import com.example.ossdoc.domain.extraction.dto.context.ExtractionSink;
import com.example.ossdoc.domain.extraction.dto.model.EvidenceFact;
import com.example.ossdoc.domain.extraction.dto.model.ObservationFact;
import com.example.ossdoc.domain.extraction.dto.model.TypeRef;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JavaParserSemanticObservationEvidenceTest {

    private final JavaParserAstFactsExtractor extractor =
            new JavaParserAstFactsExtractor();

    @Test
    @DisplayName("HTTP_ENDPOINT가 클래스·메서드 매핑 어노테이션 Evidence를 참조한다")
    void endpointUsesMappingAnnotationEvidence() throws Exception {
        String source = """
                @RequestMapping("/api")
                class UserController {
                    @GetMapping("/users")
                    void users() {}
                }
                """;

        CompilationUnit unit = StaticJavaParser.parse(source);
        TypeDeclaration<?> type = unit.getType(0);
        MethodDeclaration method = type.getMethodsByName("users").get(0);
        ExtractionSink sink = new ExtractionSink();

        invoke(
                "addHttpEndpointObservationsIfNeeded",
                new Class<?>[]{
                        TypeDeclaration.class,
                        MethodDeclaration.class,
                        String.class,
                        String.class,
                        List.class,
                        String.class,
                        ExtractionSink.class
                },
                type,
                method,
                "method:sample.UserController#users()",
                "src/main/java/sample/UserController.java",
                source.lines().toList(),
                "method-evidence",
                sink
        );

        ObservationFact endpoint = sink.toExtractedFacts()
                .observations()
                .httpEndpoints()
                .get(0);

        List<EvidenceFact> evidence = evidenceOf(sink, endpoint.evidenceIds());
        Set<String> snippets = evidence.stream()
                .map(EvidenceFact::snippet)
                .collect(Collectors.toSet());

        assertEquals(
                Set.of(
                        "@RequestMapping(\"/api\")",
                        "@GetMapping(\"/users\")"
                ),
                snippets
        );
        assertTrue(evidence.stream().allMatch(item ->
                "annotation".equals(item.attrs().get("granularity"))
                        && "endpoint_mapping".equals(item.attrs().get("role"))
        ));
    }

    @Test
    @DisplayName("Bean provider와 Configuration wiring이 관련 어노테이션 Evidence만 참조한다")
    void beanAndConfigurationUseAnnotationEvidence() throws Exception {
        ExtractionContext context = observationContext();

        String componentSource = """
                @Component
                @Primary
                class UserService {}
                """;
        CompilationUnit componentUnit = StaticJavaParser.parse(componentSource);
        TypeDeclaration<?> componentType = componentUnit.getType(0);
        ExtractionSink componentSink = new ExtractionSink();

        invoke(
                "addTypeObservationsIfNeeded",
                new Class<?>[]{
                        ExtractionContext.class,
                        TypeDeclaration.class,
                        String.class,
                        String.class,
                        List.class,
                        String.class,
                        ExtractionSink.class
                },
                context,
                componentType,
                "type:sample.UserService",
                "src/main/java/sample/UserService.java",
                componentSource.lines().toList(),
                "type-evidence",
                componentSink
        );

        ObservationFact provider = componentSink.toExtractedFacts()
                .observations()
                .diProviders()
                .get(0);
        List<EvidenceFact> providerEvidence =
                evidenceOf(componentSink, provider.evidenceIds());

        assertEquals(
                Set.of("@Component", "@Primary"),
                providerEvidence.stream()
                        .map(EvidenceFact::snippet)
                        .collect(Collectors.toSet())
        );
        assertTrue(providerEvidence.stream().allMatch(item ->
                "bean_provider".equals(item.attrs().get("role"))
        ));

        String configSource = """
                @Configuration
                @Import(SecurityConfig.class)
                @ComponentScan("sample.feature")
                class AppConfig {}
                """;
        CompilationUnit configUnit = StaticJavaParser.parse(configSource);
        TypeDeclaration<?> configType = configUnit.getType(0);
        ExtractionSink configSink = new ExtractionSink();

        invoke(
                "addTypeObservationsIfNeeded",
                new Class<?>[]{
                        ExtractionContext.class,
                        TypeDeclaration.class,
                        String.class,
                        String.class,
                        List.class,
                        String.class,
                        ExtractionSink.class
                },
                context,
                configType,
                "type:sample.AppConfig",
                "src/main/java/sample/AppConfig.java",
                configSource.lines().toList(),
                "type-evidence",
                configSink
        );

        ObservationFact wiring = configSink.toExtractedFacts()
                .observations()
                .configWiring()
                .get(0);
        List<EvidenceFact> wiringEvidence =
                evidenceOf(configSink, wiring.evidenceIds());

        assertEquals(3, wiringEvidence.size());
        assertTrue(wiringEvidence.stream().allMatch(item ->
                "annotation".equals(item.attrs().get("granularity"))
                        && "configuration_wiring".equals(item.attrs().get("role"))
        ));
    }

    @Test
    @DisplayName("필드 주입은 주입 어노테이션과 정확한 필드 선언 Evidence를 함께 참조한다")
    void fieldInjectionUsesAnnotationAndFieldEvidence() throws Exception {
        String source = """
                class UserController {
                    @Autowired
                    private UserService userService;
                }
                """;

        CompilationUnit unit = StaticJavaParser.parse(source);
        FieldDeclaration field = unit.findFirst(FieldDeclaration.class)
                .orElseThrow();
        VariableDeclarator variable = field.getVariable(0);
        ExtractionSink sink = new ExtractionSink();

        invoke(
                "addFieldObservationsIfNeeded",
                new Class<?>[]{
                        ExtractionContext.class,
                        FieldDeclaration.class,
                        VariableDeclarator.class,
                        String.class,
                        TypeRef.class,
                        String.class,
                        List.class,
                        String.class,
                        ExtractionSink.class
                },
                observationContext(),
                field,
                variable,
                "field:sample.UserController#userService",
                TypeRef.builder().raw("sample.UserService").build(),
                "src/main/java/sample/UserController.java",
                source.lines().toList(),
                "field-evidence",
                sink
        );

        ObservationFact injection = sink.toExtractedFacts()
                .observations()
                .diInjectionSites()
                .get(0);
        List<EvidenceFact> evidence = evidenceOf(sink, injection.evidenceIds());

        Map<String, EvidenceFact> byRole = evidence.stream()
                .collect(Collectors.toMap(
                        item -> String.valueOf(item.attrs().get("role")),
                        item -> item
                ));

        assertEquals("@Autowired", byRole.get("injection_annotation").snippet());
        EvidenceFact fieldEvidence =
                byRole.get("injection_field");

        assertEquals(
                "UserService userService",
                fieldEvidence.snippet(),
                () -> "실제 field snippet = ["
                        + fieldEvidence.snippet()
                        + "]"
        );
        assertEquals(
                "member",
                byRole.get("injection_field").attrs().get("granularity")
        );
    }

    @Test
    @DisplayName("생성자 주입은 각 파라미터 범위 Evidence를 분리한다")
    void constructorInjectionUsesParameterEvidence() throws Exception {
        String source = """
                class UserController {
                    @Autowired
                    UserController(UserService userService, AuditService auditService) {}
                }
                """;

        CompilationUnit unit = StaticJavaParser.parse(source);
        ConstructorDeclaration constructor =
                unit.findFirst(ConstructorDeclaration.class).orElseThrow();
        ExtractionSink sink = new ExtractionSink();

        invoke(
                "addConstructorObservationsIfNeeded",
                new Class<?>[]{
                        ExtractionContext.class,
                        ConstructorDeclaration.class,
                        String.class,
                        String.class,
                        List.class,
                        String.class,
                        ExtractionSink.class
                },
                observationContext(),
                constructor,
                "constructor:sample.UserController(UserService,AuditService)",
                "src/main/java/sample/UserController.java",
                source.lines().toList(),
                "constructor-evidence",
                sink
        );

        List<ObservationFact> injections = sink.toExtractedFacts()
                .observations()
                .diInjectionSites();

        assertEquals(2, injections.size());
        Set<String> parameterSnippets = injections.stream()
                .flatMap(observation ->
                        evidenceOf(sink, observation.evidenceIds()).stream())
                .filter(item ->
                        "constructor_parameter".equals(item.attrs().get("role")))
                .map(EvidenceFact::snippet)
                .collect(Collectors.toSet());

        assertEquals(
                Set.of(
                        "UserService userService",
                        "AuditService auditService"
                ),
                parameterSnippets
        );
        assertFalse(parameterSnippets.contains(constructor.toString()));
        assertTrue(injections.stream().allMatch(observation ->
                evidenceOf(sink, observation.evidenceIds()).stream()
                        .anyMatch(item ->
                                "injection_annotation".equals(
                                        item.attrs().get("role")
                                )
                        )
        ));
    }

    private ExtractionContext observationContext() {
        ExtractionContext context = mock(ExtractionContext.class);
        when(context.includeObservations()).thenReturn(true);
        return context;
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
