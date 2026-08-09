package com.example.ossdoc.domain.graphstore.service.promotion;

import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedFactsDocument;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedObservationFact;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedRelationFact;
import com.example.ossdoc.domain.graphstore.model.promotion.ObservationPromotionCandidateGenerationResult;
import com.example.ossdoc.domain.graphstore.model.promotion.ObservationPromotionShadowCandidate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndpointEventSpiShadowCandidateGeneratorTest {

    private final ObjectMapper objectMapper =
            new ObjectMapper()
                    .findAndRegisterModules();

    @Test
    @DisplayName("HTTP method·path 조합마다 HANDLES_ENDPOINT 후보를 생성한다")
    void generatesEndpointCartesianProduct() {
        var attrs =
                JsonNodeFactory.instance.objectNode();

        attrs.putArray("http_methods")
                .add("get")
                .add("post");

        attrs.putArray("paths")
                .add("/users")
                .add("users/{id}");

        NormalizedObservationFact observation =
                observation(
                        "http_endpoint",
                        "method:sample.UserController#handle()",
                        null,
                        null,
                        "ast",
                        attrs,
                        List.of("endpoint-annotation")
                );

        ObservationPromotionCandidateGenerationResult result =
                generate(List.of(observation));

        assertEquals(1, result.eligibleObservationCount());
        assertEquals(4, result.candidates().size());
        assertTrue(result.warnings().isEmpty());

        assertEquals(
                List.of(
                        "GET /users",
                        "GET /users/{id}",
                        "POST /users",
                        "POST /users/{id}"
                ),
                result.candidates().stream()
                        .map(candidate ->
                                candidate.relation()
                                        .dstRawRef()
                        )
                        .toList()
        );

        for (ObservationPromotionShadowCandidate candidate
                : result.candidates()) {
            NormalizedRelationFact relation =
                    candidate.relation();

            assertEquals(
                    "handles_endpoint",
                    relation.kind()
            );
            assertEquals("ast", relation.origin());
            assertEquals("derived", relation.derivation());
            assertEquals(
                    "EndpointObservationResolver",
                    relation.attrs().get("resolver")
            );
            assertEquals(
                    "http_endpoint",
                    relation.attrs().get("semantic_kind")
            );
            assertEquals(
                    List.of("endpoint-annotation"),
                    relation.evidenceIds()
            );
        }
    }

    @Test
    @DisplayName("해석되지 않은 HTTP path는 명시적인 raw target 후보로 유지한다")
    void generatesUnresolvedEndpointCandidate() {
        var attrs =
                JsonNodeFactory.instance.objectNode();

        attrs.putArray("http_methods")
                .add("PATCH");

        attrs.put(
                "path_resolution",
                "unresolved"
        );

        NormalizedRelationFact relation =
                generate(List.of(observation(
                        "http_endpoint",
                        "method:sample.Api#patch()",
                        null,
                        null,
                        "observed",
                        attrs,
                        List.of()
                ))).candidates()
                        .get(0)
                        .relation();

        assertEquals(
                "PATCH <unresolved-path>",
                relation.dstRawRef()
        );
        assertEquals(
                "<unresolved-path>",
                relation.attrs().get("path")
        );
        assertEquals(
                "HTTP endpoint path could not be resolved",
                relation.resolutionReason()
        );
    }

    @Test
    @DisplayName("Event target symbol을 PUBLISHES_EVENT와 LISTENS_EVENT 후보로 변환한다")
    void generatesResolvedEventCandidates() {
        NormalizedObservationFact publication =
                observation(
                        "event_publication",
                        "method:sample.Service#create()",
                        "type:sample.CreatedEvent",
                        null,
                        "observed",
                        null,
                        List.of("publish-call")
                );

        NormalizedObservationFact subscription =
                observation(
                        "event_subscription",
                        "method:sample.Listener#handle(sample.CreatedEvent)",
                        "type:sample.CreatedEvent",
                        null,
                        "bytecode",
                        null,
                        List.of("listener-annotation")
                );

        ObservationPromotionCandidateGenerationResult result =
                generate(
                        List.of(
                                publication,
                                subscription
                        )
                );

        assertEquals(2, result.candidates().size());

        NormalizedRelationFact publishes =
                result.candidates().get(0).relation();

        NormalizedRelationFact listens =
                result.candidates().get(1).relation();

        assertEquals(
                "publishes_event",
                publishes.kind()
        );
        assertEquals(
                "type:sample.CreatedEvent",
                publishes.dstSymbol()
        );
        assertNull(publishes.dstRawRef());
        assertEquals(
                "event_publication",
                publishes.attrs().get("semantic_kind")
        );

        assertEquals(
                "listens_event",
                listens.kind()
        );
        assertEquals("bytecode", listens.origin());
        assertEquals(
                "event_subscription",
                listens.attrs().get("semantic_kind")
        );
    }

    @Test
    @DisplayName("unresolved Event TypeRef는 event raw reference로 보존한다")
    void generatesUnresolvedEventCandidate() {
        var typeRef =
                JsonNodeFactory.instance.objectNode();

        typeRef.put("raw", "CreatedEvent");
        typeRef.put("unresolved", true);

        NormalizedRelationFact relation =
                generate(List.of(observation(
                        "event_publication",
                        "method:sample.Service#create()",
                        null,
                        typeRef,
                        "observed",
                        null,
                        List.of("publish-call")
                ))).candidates()
                        .get(0)
                        .relation();

        assertNull(relation.dstSymbol());
        assertEquals(
                "event:CreatedEvent",
                relation.dstRawRef()
        );
        assertEquals(
                "CreatedEvent",
                relation.attrs().get("event_type")
        );
    }

    @Test
    @DisplayName("SPI provider와 ServiceLoader·module uses/provides 후보를 독립 생성한다")
    void generatesSpiCandidates() {
        var providerAttrs =
                JsonNodeFactory.instance.objectNode();

        providerAttrs.put(
                "implementation",
                "sample.JsonProviderImpl"
        );

        NormalizedObservationFact provider =
                observation(
                        "spi_provider",
                        "resource:META-INF/services/sample.JsonProvider",
                        "type:sample.JsonProvider",
                        null,
                        "resource",
                        providerAttrs,
                        List.of("service-entry")
                );

        NormalizedObservationFact loader =
                observation(
                        "spi_provider",
                        "method:sample.Loader#load()",
                        "type:sample.JsonProvider",
                        null,
                        "bytecode",
                        null,
                        List.of("service-loader")
                );

        NormalizedObservationFact moduleUses =
                observation(
                        "module_uses",
                        "module:sample.app",
                        "type:sample.JsonProvider",
                        null,
                        "ast",
                        null,
                        List.of("module-uses")
                );

        var moduleProvidesAttrs =
                JsonNodeFactory.instance.objectNode();

        moduleProvidesAttrs.put(
                "implementation_type",
                "sample.JsonProviderImpl"
        );

        NormalizedObservationFact moduleProvides =
                observation(
                        "module_provides",
                        "module:sample.provider",
                        "type:sample.JsonProvider",
                        null,
                        "ast",
                        moduleProvidesAttrs,
                        List.of("module-provides")
                );

        ObservationPromotionCandidateGenerationResult result =
                generate(List.of(
                        provider,
                        loader,
                        moduleUses,
                        moduleProvides
                ));

        assertEquals(4, result.candidates().size());

        assertEquals(
                List.of(
                        "provides_spi",
                        "loads_service",
                        "loads_service",
                        "provides_spi"
                ),
                result.candidates().stream()
                        .map(candidate ->
                                candidate.relation().kind()
                        )
                        .toList()
        );

        NormalizedRelationFact providerRelation =
                result.candidates().get(0).relation();

        assertEquals(
                "type:sample.JsonProviderImpl",
                providerRelation.srcSymbol()
        );
        assertEquals(
                "type:sample.JsonProvider",
                providerRelation.dstSymbol()
        );
        assertEquals(
                "SPI_PROVIDER",
                providerRelation.attrs()
                        .get("source_observation_kind")
        );

        NormalizedRelationFact loaderRelation =
                result.candidates().get(1).relation();

        assertEquals(
                "method:sample.Loader#load()",
                loaderRelation.srcSymbol()
        );
        assertEquals(
                "service_loader",
                loaderRelation.attrs().get("mechanism")
        );

        NormalizedRelationFact moduleUsesRelation =
                result.candidates().get(2).relation();

        assertEquals(
                "module_uses",
                moduleUsesRelation.attrs().get("mechanism")
        );

        NormalizedRelationFact moduleProvidesRelation =
                result.candidates().get(3).relation();

        assertEquals(
                "module_provides",
                moduleProvidesRelation.attrs().get("mechanism")
        );
        assertEquals(
                "explicit_implementation",
                moduleProvidesRelation.attrs()
                        .get("provider_resolution")
        );
    }

    @Test
    @DisplayName("비대상 Observation은 후보 생성에서 제외한다")
    void ignoresNonTargetObservations() {
        ObservationPromotionCandidateGenerationResult result =
                generate(List.of(observation(
                        "di_provider",
                        "type:sample.Service",
                        null,
                        null,
                        "ast",
                        null,
                        List.of()
                )));

        assertEquals(0, result.eligibleObservationCount());
        assertTrue(result.candidates().isEmpty());
    }

    private ObservationPromotionCandidateGenerationResult generate(
            List<NormalizedObservationFact> observations
    ) {
        return EndpointEventSpiShadowCandidateGenerator.generate(
                new NormalizedFactsDocument(
                        "2",
                        Map.of(),
                        List.of(),
                        List.of(),
                        observations
                ),
                objectMapper
        );
    }

    private NormalizedObservationFact observation(
            String kind,
            String siteSymbol,
            String targetSymbol,
            com.fasterxml.jackson.databind.JsonNode targetTypeRef,
            String origin,
            com.fasterxml.jackson.databind.JsonNode attrs,
            List<String> evidenceIds
    ) {
        return new NormalizedObservationFact(
                kind,
                siteSymbol,
                targetSymbol,
                targetTypeRef,
                null,
                origin,
                new BigDecimal("0.9"),
                attrs,
                evidenceIds
        );
    }
}
