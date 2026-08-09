package com.example.ossdoc.domain.extraction.service.composer;

import com.example.ossdoc.domain.extraction.dto.context.FactsCompositionContext;
import com.example.ossdoc.domain.extraction.dto.model.BuildMeta;
import com.example.ossdoc.domain.extraction.dto.model.EvidenceFact;
import com.example.ossdoc.domain.extraction.dto.model.ExtractionAggregate;
import com.example.ossdoc.domain.extraction.dto.model.FactsDocument;
import com.example.ossdoc.domain.extraction.dto.model.JobMeta;
import com.example.ossdoc.domain.extraction.dto.model.ObservationFact;
import com.example.ossdoc.domain.extraction.dto.model.ObservationTable;
import com.example.ossdoc.domain.extraction.dto.model.RelationFact;
import com.example.ossdoc.domain.extraction.dto.model.StatsMeta;
import com.example.ossdoc.domain.extraction.dto.model.SymbolFact;
import com.example.ossdoc.domain.extraction.dto.model.SymbolTable;
import com.example.ossdoc.domain.extraction.dto.model.TypeRef;
import com.example.ossdoc.domain.extraction.enums.DerivationKind;
import com.example.ossdoc.domain.extraction.enums.EvidenceType;
import com.example.ossdoc.domain.extraction.enums.ExtractionMode;
import com.example.ossdoc.domain.extraction.enums.FactOriginKind;
import com.example.ossdoc.domain.extraction.enums.ObservationKind;
import com.example.ossdoc.domain.extraction.enums.ResolutionStatus;
import com.example.ossdoc.domain.extraction.enums.SymbolKind;
import com.example.ossdoc.domain.extraction.service.support.policy.RelationConfidencePolicy;
import com.example.ossdoc.domain.extraction.service.support.policy.RelationResolutionPolicy;
import com.example.ossdoc.domain.extraction.service.support.resolve.BeanObservationResolver;
import com.example.ossdoc.domain.extraction.service.support.resolve.ConfigurationObservationResolver;
import com.example.ossdoc.domain.extraction.service.support.resolve.DiObservationResolver;
import com.example.ossdoc.domain.extraction.service.support.resolve.EndpointObservationResolver;
import com.example.ossdoc.domain.extraction.service.support.resolve.EventObservationResolver;
import com.example.ossdoc.domain.extraction.service.support.resolve.ObservationRelationResolutionService;
import com.example.ossdoc.domain.extraction.service.support.resolve.ReflectionObservationResolver;
import com.example.ossdoc.domain.extraction.service.support.resolve.SpiObservationResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 8-2-5 전체 Resolver 공통 정책 통합 검증.
 *
 * <p>일곱 Resolver의 Spring 등록 순서, 공통 Resolution·Confidence 정책,
 * Composer 병합, stats와 JSON 직렬화를 하나의 테스트에서 고정한다.</p>
 */
@ExtendWith(SpringExtension.class)
@Import({
        DefaultFactsComposer.class,
        ObservationRelationResolutionService.class,
        RelationResolutionPolicy.class,
        RelationConfidencePolicy.class,
        EndpointObservationResolver.class,
        BeanObservationResolver.class,
        ConfigurationObservationResolver.class,
        DiObservationResolver.class,
        EventObservationResolver.class,
        SpiObservationResolver.class,
        ReflectionObservationResolver.class
})
class SemanticRelationPolicyIntegrationTest {

    @Autowired
    private DefaultFactsComposer composer;

    @Autowired
    private ObservationRelationResolutionService resolutionService;

    @Test
    @DisplayName("전체 Resolver가 동일 정책 메타데이터를 가진 의미 관계를 생성한다")
    void appliesCommonPolicyAcrossAllResolvers() {
        FactsDocument document = composer.compose(
                contextOf(resolvedAggregate(), true)
        );

        assertNotNull(document);
        assertNotNull(document.relations());

        assertEquals(
                List.of(
                        EndpointObservationResolver.class,
                        BeanObservationResolver.class,
                        ConfigurationObservationResolver.class,
                        DiObservationResolver.class,
                        EventObservationResolver.class,
                        SpiObservationResolver.class,
                        ReflectionObservationResolver.class
                ),
                resolutionService.resolvers().stream()
                        .map(Object::getClass)
                        .toList()
        );

        assertEquals(1, document.relations().handlesEndpoint().size());
        assertEquals(1, document.relations().declaresBean().size());
        assertEquals(1, document.relations().configuresBean().size());
        assertEquals(1, document.relations().injects().size());
        assertEquals(1, document.relations().publishesEvent().size());
        assertEquals(1, document.relations().listensEvent().size());
        assertEquals(1, document.relations().loadsService().size());
        assertEquals(1, document.relations().providesSpi().size());
        assertEquals(1, document.relations().reflectsMethod().size());

        List<RelationFact> relations = semanticRelations(document);
        assertEquals(9, relations.size());

        for (RelationFact relation : relations) {
            assertEquals(
                    ResolutionStatus.RESOLVED,
                    relation.resolution().status(),
                    () -> "unexpected status for " + relation.kind()
            );
            assertEquals(DerivationKind.DERIVED, relation.derivation());
            assertNotNull(relation.confidenceHint());
            assertTrue(
                    relation.confidenceHint() >= 0.75,
                    () -> "confidence is not HIGH for " + relation.kind()
            );
            assertTrue(relation.attrs().containsKey("resolution_basis"));
            assertEquals("high", relation.attrs().get("confidence_band"));
            assertEquals(Boolean.TRUE, relation.attrs().get("default_visible"));
            assertFalse(relation.evidenceIds().isEmpty());
        }

        assertEquals(
                "exact_reference",
                document.relations().handlesEndpoint().get(0)
                        .attrs().get("resolution_basis")
        );
        assertEquals(
                "exact_reference",
                document.relations().injects().get(0)
                        .attrs().get("resolution_basis")
        );
        assertEquals(
                "exact_symbol",
                document.relations().loadsService().get(0)
                        .attrs().get("resolution_basis")
        );
        assertEquals(
                "exact_symbol",
                document.relations().reflectsMethod().get(0)
                        .attrs().get("resolution_basis")
        );

        assertEquals(9, document.stats().relations());
        assertEquals(9, document.stats().observations());
        assertTrue(document.extraction().warnings().isEmpty());

        assertJsonRelationArrays(document);
    }

    @Test
    @DisplayName("PARTIAL과 UNRESOLVED 관계는 기본 그래프 표시 대상에서 제외한다")
    void hidesPartialAndUnresolvedRelationsByCommonPolicy() {
        FactsDocument document = composer.compose(
                contextOf(nonResolvedAggregate(), true)
        );

        RelationFact endpoint = document.relations()
                .handlesEndpoint().get(0);
        assertEquals(ResolutionStatus.PARTIAL, endpoint.resolution().status());
        assertEquals("raw_reference", endpoint.attrs().get("resolution_basis"));
        assertEquals("medium", endpoint.attrs().get("confidence_band"));
        assertEquals(Boolean.FALSE, endpoint.attrs().get("default_visible"));

        RelationFact event = document.relations()
                .publishesEvent().get(0);
        assertEquals(ResolutionStatus.UNRESOLVED, event.resolution().status());
        assertEquals("unknown_target", event.attrs().get("resolution_basis"));
        assertEquals("low", event.attrs().get("confidence_band"));
        assertEquals(Boolean.FALSE, event.attrs().get("default_visible"));

        RelationFact reflection = document.relations()
                .reflectsType().get(0);
        assertEquals(
                ResolutionStatus.PARTIAL,
                reflection.resolution().status()
        );
        assertEquals(
                "raw_reference",
                reflection.attrs().get("resolution_basis")
        );
        assertEquals("medium", reflection.attrs().get("confidence_band"));
        assertEquals(Boolean.FALSE, reflection.attrs().get("default_visible"));

        assertEquals(3, document.stats().relations());
        assertEquals(3, document.stats().observations());
        assertTrue(document.extraction().warnings().isEmpty());
    }

    @Test
    @DisplayName("원시 observation을 숨겨도 정책 적용된 파생 관계는 유지한다")
    void keepsPolicyRelationsWhenObservationsAreExcluded() {
        FactsDocument document = composer.compose(
                contextOf(resolvedAggregate(), false)
        );

        assertEquals(9, document.stats().relations());
        assertEquals(0, document.stats().observations());
        assertEquals(9, semanticRelations(document).size());

        assertTrue(document.observations().httpEndpoints().isEmpty());
        assertTrue(document.observations().diProviders().isEmpty());
        assertTrue(document.observations().diInjectionSites().isEmpty());
        assertTrue(document.observations().configWiring().isEmpty());
        assertTrue(document.observations().eventPublications().isEmpty());
        assertTrue(document.observations().eventSubscriptions().isEmpty());
        assertTrue(document.observations().spiProviders().isEmpty());
        assertTrue(document.observations().moduleProvides().isEmpty());
        assertTrue(document.observations().reflectionSites().isEmpty());
    }

    private FactsCompositionContext contextOf(
            ExtractionAggregate aggregate,
            boolean includeObservations
    ) {
        OffsetDateTime now = OffsetDateTime.now();
        return new FactsCompositionContext(
                "test-schema",
                JobMeta.builder().build(),
                BuildMeta.builder().build(),
                ExtractionMode.AST_PLUS_BYTECODE,
                now,
                now,
                List.of(),
                includeObservations,
                aggregate
        );
    }

    private ExtractionAggregate resolvedAggregate() {
        String injectionFieldSymbol =
                "field:sample.UserController#userService";

        ObservationFact endpoint = ObservationFact.builder()
                .kind(ObservationKind.HTTP_ENDPOINT)
                .siteSymbol("method:sample.UserController#findUsers()")
                .evidenceIds(List.of("ev-endpoint"))
                .origin(FactOriginKind.AST)
                .confidenceHint(0.9)
                .attrs(Map.of(
                        "framework", "spring_mvc",
                        "http_methods", List.of("GET"),
                        "paths", List.of("/api/users"),
                        "path_resolution", "resolved"
                ))
                .build();

        ObservationFact provider = ObservationFact.builder()
                .kind(ObservationKind.DI_PROVIDER)
                .siteSymbol("method:sample.AppConfig#userService()")
                .targetTypeRef(typeRef("sample.UserService", false))
                .evidenceIds(List.of(
                        "ev-provider-ast",
                        "ev-provider-bytecode"
                ))
                .origin(FactOriginKind.AST_AND_BYTECODE)
                .confidenceHint(0.9)
                .attrs(Map.of(
                        "provider_kind", "bean_method",
                        "bean_names", List.of("userService"),
                        "provided_type", "sample.UserService",
                        "primary", true,
                        "qualifiers", List.of("userService"),
                        "owner_config_symbol", "type:sample.AppConfig"
                ))
                .build();

        ObservationFact configuration = ObservationFact.builder()
                .kind(ObservationKind.CONFIG_WIRING)
                .siteSymbol("type:sample.AppConfig")
                .evidenceIds(List.of("ev-config"))
                .origin(FactOriginKind.AST)
                .confidenceHint(0.9)
                .attrs(Map.of(
                        "configuration_kind", "spring_configuration",
                        "configuration_kinds", List.of(
                                "spring_configuration",
                                "spring_import"
                        ),
                        "imported_types", List.of("sample.SecurityConfig"),
                        "component_scan_packages", List.of(),
                        "component_scan_base_package_classes", List.of()
                ))
                .build();

        ObservationFact injection = ObservationFact.builder()
                .kind(ObservationKind.DI_INJECTION_SITE)
                .siteSymbol(injectionFieldSymbol)
                .targetTypeRef(typeRef("sample.UserService", false))
                .evidenceIds(List.of("ev-injection"))
                .origin(FactOriginKind.AST)
                .confidenceHint(0.9)
                .attrs(Map.of("qualifier", "userService"))
                .build();

        ObservationFact publication = ObservationFact.builder()
                .kind(ObservationKind.EVENT_PUBLICATION)
                .siteSymbol("method:sample.OrderService#createOrder()")
                .targetSymbol("sample.OrderCreatedEvent")
                .evidenceIds(List.of("ev-publish"))
                .origin(FactOriginKind.AST)
                .confidenceHint(0.9)
                .attrs(Map.of("method", "publishEvent"))
                .build();

        ObservationFact subscription = ObservationFact.builder()
                .kind(ObservationKind.EVENT_SUBSCRIPTION)
                .siteSymbol("method:sample.OrderListener#handle(sample.OrderCreatedEvent)")
                .targetTypeRef(typeRef("sample.OrderCreatedEvent", false))
                .evidenceIds(List.of("ev-listen"))
                .origin(FactOriginKind.AST_AND_BYTECODE)
                .confidenceHint(0.9)
                .attrs(Map.of("annotations", List.of("EventListener")))
                .build();

        ObservationFact serviceLoader = ObservationFact.builder()
                .kind(ObservationKind.SPI_PROVIDER)
                .siteSymbol("method:sample.PluginRegistry#loadPlugins()")
                .targetSymbol("sample.Plugin")
                .evidenceIds(List.of("ev-loader"))
                .origin(FactOriginKind.AST)
                .confidenceHint(0.9)
                .build();

        ObservationFact moduleProvides = ObservationFact.builder()
                .kind(ObservationKind.MODULE_PROVIDES)
                .siteSymbol("module:sample.plugin")
                .targetSymbol("sample.Plugin")
                .evidenceIds(List.of("ev-provides"))
                .origin(FactOriginKind.AST)
                .confidenceHint(0.9)
                .attrs(Map.of("implementation", "sample.DefaultPlugin"))
                .build();

        ObservationFact reflection = ObservationFact.builder()
                .kind(ObservationKind.REFLECTION_SITE)
                .siteSymbol("method:sample.ReflectionClient#invokeRun()")
                .evidenceIds(List.of("ev-reflection"))
                .origin(FactOriginKind.AST)
                .confidenceHint(0.9)
                .attrs(Map.of(
                        "api_method", "getDeclaredMethod",
                        "reflection_kind", "method",
                        "target_type", "sample.ReflectTarget",
                        "member_name", "run",
                        "parameter_types", List.of("java.lang.String")
                ))
                .build();

        SymbolFact injectionField = symbol(
                injectionFieldSymbol,
                SymbolKind.FIELD,
                "userService",
                "type:sample.UserController",
                null
        );
        SymbolFact reflectType = symbol(
                "type:sample.ReflectTarget",
                SymbolKind.TYPE,
                "ReflectTarget",
                null,
                "sample.ReflectTarget"
        );
        SymbolFact reflectMethod = symbol(
                "method:sample.ReflectTarget#run(java.lang.String)",
                SymbolKind.METHOD,
                "run",
                "type:sample.ReflectTarget",
                null
        );

        return ExtractionAggregate.builder()
                .evidence(Map.of(
                        "ev-endpoint", evidence("ev-endpoint", EvidenceType.AST),
                        "ev-provider-ast", evidence("ev-provider-ast", EvidenceType.AST),
                        "ev-provider-bytecode", evidence("ev-provider-bytecode", EvidenceType.BYTECODE),
                        "ev-config", evidence("ev-config", EvidenceType.AST),
                        "ev-injection", evidence("ev-injection", EvidenceType.AST),
                        "ev-publish", evidence("ev-publish", EvidenceType.AST),
                        "ev-listen", evidence("ev-listen", EvidenceType.BYTECODE),
                        "ev-loader", evidence("ev-loader", EvidenceType.AST),
                        "ev-provides", evidence("ev-provides", EvidenceType.AST),
                        "ev-reflection", evidence("ev-reflection", EvidenceType.AST)
                ))
                .symbols(SymbolTable.builder()
                        .types(List.of(reflectType))
                        .constructors(List.of())
                        .methods(List.of(reflectMethod))
                        .fields(List.of(injectionField))
                        .build())
                .observations(ObservationTable.builder()
                        .httpEndpoints(List.of(endpoint))
                        .diProviders(List.of(provider))
                        .configWiring(List.of(configuration))
                        .diInjectionSites(List.of(injection))
                        .eventPublications(List.of(publication))
                        .eventSubscriptions(List.of(subscription))
                        .spiProviders(List.of(serviceLoader))
                        .moduleProvides(List.of(moduleProvides))
                        .reflectionSites(List.of(reflection))
                        .build())
                .stats(StatsMeta.builder().build())
                .warnings(List.of())
                .build();
    }

    private ExtractionAggregate nonResolvedAggregate() {
        ObservationFact endpoint = ObservationFact.builder()
                .kind(ObservationKind.HTTP_ENDPOINT)
                .siteSymbol("method:sample.UserController#search()")
                .origin(FactOriginKind.AST)
                .confidenceHint(0.9)
                .attrs(Map.of(
                        "http_methods", List.of("GET"),
                        "paths", List.of(),
                        "path_resolution", "unresolved",
                        "method_mapping_attributes", Map.of(
                                "value", "ApiPaths.SEARCH"
                        )
                ))
                .build();

        ObservationFact event = ObservationFact.builder()
                .kind(ObservationKind.EVENT_PUBLICATION)
                .siteSymbol("method:sample.OrderService#createOrder()")
                .origin(FactOriginKind.AST)
                .build();

        ObservationFact reflection = ObservationFact.builder()
                .kind(ObservationKind.REFLECTION_SITE)
                .siteSymbol("method:sample.ReflectionClient#loadExternal()")
                .evidenceIds(List.of("ev-external-reflection"))
                .origin(FactOriginKind.AST)
                .confidenceHint(0.9)
                .attrs(Map.of(
                        "api_method", "forName",
                        "reflection_kind", "type",
                        "target_type", "external.lib.Plugin"
                ))
                .build();

        return ExtractionAggregate.builder()
                .evidence(Map.of(
                        "ev-external-reflection",
                        evidence("ev-external-reflection", EvidenceType.AST)
                ))
                .symbols(SymbolTable.builder()
                        .types(List.of())
                        .constructors(List.of())
                        .methods(List.of())
                        .fields(List.of())
                        .build())
                .observations(ObservationTable.builder()
                        .httpEndpoints(List.of(endpoint))
                        .eventPublications(List.of(event))
                        .reflectionSites(List.of(reflection))
                        .build())
                .stats(StatsMeta.builder().build())
                .warnings(List.of())
                .build();
    }

    private List<RelationFact> semanticRelations(FactsDocument document) {
        return Stream.of(
                        document.relations().handlesEndpoint(),
                        document.relations().declaresBean(),
                        document.relations().configuresBean(),
                        document.relations().injects(),
                        document.relations().publishesEvent(),
                        document.relations().listensEvent(),
                        document.relations().providesSpi(),
                        document.relations().loadsService(),
                        document.relations().reflectsType(),
                        document.relations().reflectsMethod(),
                        document.relations().reflectsField(),
                        document.relations().reflectsConstructor()
                )
                .filter(list -> list != null)
                .flatMap(List::stream)
                .toList();
    }

    private EvidenceFact evidence(String id, EvidenceType type) {
        return EvidenceFact.builder()
                .id(id)
                .type(type)
                .path("src/main/java/sample/SemanticPolicySample.java")
                .symbol("type:sample.SemanticPolicySample")
                .snippet(id)
                .build();
    }

    private TypeRef typeRef(String raw, boolean unresolved) {
        return TypeRef.builder()
                .raw(raw)
                .arrayDim(0)
                .primitive(false)
                .unresolved(unresolved)
                .sourceText(raw)
                .build();
    }

    private SymbolFact symbol(
            String symbol,
            SymbolKind kind,
            String name,
            String ownerSymbol,
            String qualifiedName
    ) {
        return SymbolFact.builder()
                .symbol(symbol)
                .kind(kind)
                .name(name)
                .ownerSymbol(ownerSymbol)
                .qualifiedName(qualifiedName)
                .build();
    }

    private void assertJsonRelationArrays(FactsDocument document) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        JsonNode json = mapper.valueToTree(document);

        for (String relationName : List.of(
                "handles_endpoint",
                "declares_bean",
                "configures_bean",
                "injects",
                "publishes_event",
                "listens_event",
                "loads_service",
                "provides_spi",
                "reflects_method"
        )) {
            JsonNode relations = json.path("relations").path(relationName);
            assertTrue(relations.isArray(), relationName);
            assertEquals(1, relations.size(), relationName);
            assertTrue(
                    relations.get(0).path("attrs")
                            .has("resolution_basis"),
                    relationName
            );
            assertEquals(
                    "high",
                    relations.get(0).path("attrs")
                            .path("confidence_band")
                            .asText(),
                    relationName
            );
            assertTrue(
                    relations.get(0).path("attrs")
                            .path("default_visible")
                            .asBoolean(),
                    relationName
            );
        }
    }
}
