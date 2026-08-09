package com.example.ossdoc.domain.extraction.service.composer;

import com.example.ossdoc.domain.extraction.dto.context.FactsCompositionContext;
import com.example.ossdoc.domain.extraction.dto.model.BuildMeta;
import com.example.ossdoc.domain.extraction.dto.model.EvidenceFact;
import com.example.ossdoc.domain.extraction.dto.model.ExtractionAggregate;
import com.example.ossdoc.domain.extraction.dto.model.FactsDocument;
import com.example.ossdoc.domain.extraction.dto.model.JobMeta;
import com.example.ossdoc.domain.extraction.dto.model.ObservationFact;
import com.example.ossdoc.domain.extraction.dto.model.ObservationTable;
import com.example.ossdoc.domain.extraction.dto.model.StatsMeta;
import com.example.ossdoc.domain.extraction.dto.model.SymbolFact;
import com.example.ossdoc.domain.extraction.dto.model.SymbolTable;
import com.example.ossdoc.domain.extraction.dto.model.TypeRef;
import com.example.ossdoc.domain.extraction.enums.DerivationKind;
import com.example.ossdoc.domain.extraction.enums.EvidenceType;
import com.example.ossdoc.domain.extraction.enums.ExtractionMode;
import com.example.ossdoc.domain.extraction.enums.FactOriginKind;
import com.example.ossdoc.domain.extraction.enums.ObservationKind;
import com.example.ossdoc.domain.extraction.enums.RelationKind;
import com.example.ossdoc.domain.extraction.enums.ResolutionStatus;
import com.example.ossdoc.domain.extraction.enums.SymbolKind;
import com.example.ossdoc.domain.extraction.service.support.resolve.BeanObservationResolver;
import com.example.ossdoc.domain.extraction.service.support.resolve.ConfigurationObservationResolver;
import com.example.ossdoc.domain.extraction.service.support.resolve.DiObservationResolver;
import com.example.ossdoc.domain.extraction.service.support.resolve.EndpointObservationResolver;
import com.example.ossdoc.domain.extraction.service.support.resolve.ObservationRelationResolutionService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Observation Resolver 전체 통합 테스트.
 *
 * <p>실제 Spring 등록 순서, Resolver 오케스트레이션, Composer 병합,
 * stats 재계산 및 JSON 직렬화를 한 번에 검증한다.</p>
 */
@ExtendWith(SpringExtension.class)
@Import({
        DefaultFactsComposer.class,
        ObservationRelationResolutionService.class,
        EndpointObservationResolver.class,
        BeanObservationResolver.class,
        ConfigurationObservationResolver.class,
        DiObservationResolver.class
})
class SemanticRelationResolutionIntegrationTest {

    private static final String ENDPOINT_EVIDENCE = "ev-endpoint";
    private static final String PROVIDER_AST_EVIDENCE = "ev-provider-ast";
    private static final String PROVIDER_BYTECODE_EVIDENCE = "ev-provider-bytecode";
    private static final String CONFIG_EVIDENCE = "ev-config";
    private static final String INJECTION_EVIDENCE = "ev-injection";

    @Autowired
    private DefaultFactsComposer composer;

    @Autowired
    private ObservationRelationResolutionService resolutionService;

    @Test
    @DisplayName("네 Resolver가 하나의 aggregate를 네 종류 의미 관계로 승격한다")
    void resolvesAllSemanticRelationsInOneComposition() {
        FactsDocument document = composer.compose(
                contextOf(true)
        );

        assertNotNull(document);
        assertNotNull(document.relations());

        assertEquals(1, document.relations().handlesEndpoint().size());
        assertEquals(1, document.relations().declaresBean().size());
        assertEquals(1, document.relations().configuresBean().size());
        assertEquals(1, document.relations().injects().size());

        assertEquals(
                List.of(
                        EndpointObservationResolver.class,
                        BeanObservationResolver.class,
                        ConfigurationObservationResolver.class,
                        DiObservationResolver.class
                ),
                resolutionService.resolvers().stream()
                        .map(Object::getClass)
                        .toList()
        );

        var endpoint = document.relations().handlesEndpoint().get(0);
        assertEquals(RelationKind.HANDLES_ENDPOINT, endpoint.kind());
        assertEquals("GET /api/users", endpoint.dstRawRef());
        assertEquals(ResolutionStatus.RESOLVED, endpoint.resolution().status());
        assertEquals(DerivationKind.DERIVED, endpoint.derivation());
        assertEquals(FactOriginKind.AST, endpoint.origin());
        assertEquals(List.of(ENDPOINT_EVIDENCE), endpoint.evidenceIds());

        var bean = document.relations().declaresBean().get(0);
        assertEquals(RelationKind.DECLARES_BEAN, bean.kind());
        assertEquals("bean:userService", bean.dstRawRef());
        assertEquals(ResolutionStatus.RESOLVED, bean.resolution().status());
        assertEquals(DerivationKind.DERIVED, bean.derivation());
        assertEquals(FactOriginKind.AST_AND_BYTECODE, bean.origin());
        assertEquals(
                List.of(
                        PROVIDER_AST_EVIDENCE,
                        PROVIDER_BYTECODE_EVIDENCE
                ),
                bean.evidenceIds()
        );

        var configuration = document.relations().configuresBean().get(0);
        assertEquals(RelationKind.CONFIGURES_BEAN, configuration.kind());
        assertEquals(
                "type:sample.SecurityConfig",
                configuration.dstRawRef()
        );
        assertEquals(
                ResolutionStatus.RESOLVED,
                configuration.resolution().status()
        );
        assertEquals(DerivationKind.DERIVED, configuration.derivation());
        assertEquals(FactOriginKind.AST, configuration.origin());
        assertEquals(List.of(CONFIG_EVIDENCE), configuration.evidenceIds());

        var injection = document.relations().injects().get(0);
        assertEquals(RelationKind.INJECTS, injection.kind());
        assertEquals("type:sample.UserController", injection.srcSymbol());
        assertEquals("bean:userService", injection.dstRawRef());
        assertEquals(ResolutionStatus.RESOLVED, injection.resolution().status());
        assertEquals(DerivationKind.DERIVED, injection.derivation());
        assertEquals(FactOriginKind.AST_AND_BYTECODE, injection.origin());
        assertEquals(
                List.of(
                        INJECTION_EVIDENCE,
                        PROVIDER_AST_EVIDENCE,
                        PROVIDER_BYTECODE_EVIDENCE
                ),
                injection.evidenceIds()
        );
        assertEquals("qualifier", injection.attrs().get("match_strategy"));

        assertEquals(4, document.stats().relations());
        assertEquals(4, document.stats().observations());
        assertTrue(document.extraction().warnings().isEmpty());

        assertJsonRelations(document);
    }

    @Test
    @DisplayName("원시 observation을 숨겨도 파생 의미 관계는 유지한다")
    void keepsDerivedRelationsWhenRawObservationsAreExcluded() {
        FactsDocument document = composer.compose(
                contextOf(false)
        );

        assertEquals(1, document.relations().handlesEndpoint().size());
        assertEquals(1, document.relations().declaresBean().size());
        assertEquals(1, document.relations().configuresBean().size());
        assertEquals(1, document.relations().injects().size());
        assertEquals(4, document.stats().relations());

        assertTrue(document.observations().httpEndpoints().isEmpty());
        assertTrue(document.observations().diProviders().isEmpty());
        assertTrue(document.observations().configWiring().isEmpty());
        assertTrue(document.observations().diInjectionSites().isEmpty());
        assertEquals(0, document.stats().observations());
        assertTrue(document.extraction().warnings().isEmpty());
    }

    private FactsCompositionContext contextOf(boolean includeObservations) {
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
                aggregate()
        );
    }

    private ExtractionAggregate aggregate() {
        String injectionSiteSymbol =
                "field:sample.UserController#userService";

        ObservationFact endpoint = ObservationFact.builder()
                .kind(ObservationKind.HTTP_ENDPOINT)
                .siteSymbol("method:sample.UserController#findUsers()")
                .evidenceIds(List.of(ENDPOINT_EVIDENCE))
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
                .targetTypeRef(typeRef("sample.UserService"))
                .evidenceIds(List.of(
                        PROVIDER_AST_EVIDENCE,
                        PROVIDER_BYTECODE_EVIDENCE
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
                .evidenceIds(List.of(CONFIG_EVIDENCE))
                .origin(FactOriginKind.AST)
                .confidenceHint(0.9)
                .attrs(Map.of(
                        "configuration_kind", "spring_configuration",
                        "configuration_kinds", List.of(
                                "spring_configuration",
                                "spring_import"
                        ),
                        "imported_types", List.of(
                                "sample.SecurityConfig"
                        ),
                        "component_scan_packages", List.of(),
                        "component_scan_base_package_classes", List.of()
                ))
                .build();

        ObservationFact injection = ObservationFact.builder()
                .kind(ObservationKind.DI_INJECTION_SITE)
                .siteSymbol(injectionSiteSymbol)
                .targetTypeRef(typeRef("sample.UserService"))
                .evidenceIds(List.of(INJECTION_EVIDENCE))
                .origin(FactOriginKind.AST)
                .confidenceHint(0.9)
                .attrs(Map.of(
                        "qualifier", "userService"
                ))
                .build();

        SymbolFact injectionField = SymbolFact.builder()
                .symbol(injectionSiteSymbol)
                .kind(SymbolKind.FIELD)
                .ownerSymbol("type:sample.UserController")
                .build();

        return ExtractionAggregate.builder()
                .evidence(Map.of(
                        ENDPOINT_EVIDENCE,
                        evidence(
                                ENDPOINT_EVIDENCE,
                                EvidenceType.AST,
                                "method:sample.UserController#findUsers()",
                                "@GetMapping(\"/api/users\")"
                        ),
                        PROVIDER_AST_EVIDENCE,
                        evidence(
                                PROVIDER_AST_EVIDENCE,
                                EvidenceType.AST,
                                "method:sample.AppConfig#userService()",
                                "@Bean(\"userService\")"
                        ),
                        PROVIDER_BYTECODE_EVIDENCE,
                        evidence(
                                PROVIDER_BYTECODE_EVIDENCE,
                                EvidenceType.BYTECODE,
                                "method:sample.AppConfig#userService()",
                                "Lorg/springframework/context/annotation/Bean;"
                        ),
                        CONFIG_EVIDENCE,
                        evidence(
                                CONFIG_EVIDENCE,
                                EvidenceType.AST,
                                "type:sample.AppConfig",
                                "@Import(SecurityConfig.class)"
                        ),
                        INJECTION_EVIDENCE,
                        evidence(
                                INJECTION_EVIDENCE,
                                EvidenceType.AST,
                                injectionSiteSymbol,
                                "@Qualifier(\"userService\")"
                        )
                ))
                .symbols(SymbolTable.builder()
                        .types(List.of())
                        .constructors(List.of())
                        .methods(List.of())
                        .fields(List.of(injectionField))
                        .build())
                .observations(ObservationTable.builder()
                        .httpEndpoints(List.of(endpoint))
                        .diProviders(List.of(provider))
                        .configWiring(List.of(configuration))
                        .diInjectionSites(List.of(injection))
                        .build())
                .stats(StatsMeta.builder().build())
                .warnings(List.of())
                .build();
    }

    private EvidenceFact evidence(
            String id,
            EvidenceType type,
            String symbol,
            String snippet
    ) {
        return EvidenceFact.builder()
                .id(id)
                .type(type)
                .path("src/main/java/sample/SemanticSample.java")
                .symbol(symbol)
                .snippet(snippet)
                .build();
    }

    private TypeRef typeRef(String raw) {
        return TypeRef.builder()
                .raw(raw)
                .arrayDim(0)
                .primitive(false)
                .unresolved(false)
                .build();
    }

    private void assertJsonRelations(FactsDocument document) {
        ObjectMapper objectMapper = new ObjectMapper()
                .findAndRegisterModules();
        JsonNode json = objectMapper.valueToTree(document);

        assertRelationArray(json, "handles_endpoint");
        assertRelationArray(json, "declares_bean");
        assertRelationArray(json, "configures_bean");
        assertRelationArray(json, "injects");
    }

    private void assertRelationArray(
            JsonNode document,
            String relationName
    ) {
        JsonNode relations = document.path("relations")
                .path(relationName);

        assertTrue(relations.isArray());
        assertEquals(1, relations.size());
        assertEquals(
                "derived",
                relations.get(0)
                        .path("derivation")
                        .asText()
        );
    }
}
