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
import com.example.ossdoc.domain.extraction.enums.EvidenceType;
import com.example.ossdoc.domain.extraction.enums.ExtractionMode;
import com.example.ossdoc.domain.extraction.enums.FactOriginKind;
import com.example.ossdoc.domain.extraction.enums.ObservationKind;
import com.example.ossdoc.domain.extraction.enums.RelationKind;
import com.example.ossdoc.domain.extraction.enums.ResolutionStatus;
import com.example.ossdoc.domain.extraction.service.support.resolve.EndpointObservationResolver;
import com.example.ossdoc.domain.extraction.service.support.resolve.ObservationRelationResolutionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndpointRelationCompositionTest {

    @Test
    @DisplayName("Composer가 HTTP_ENDPOINT를 HANDLES_ENDPOINT로 승격해 최종 relations와 stats에 반영한다")
    void composesResolvedEndpointRelation() {
        String evidenceId = "ev-http-endpoint";

        EvidenceFact evidence = EvidenceFact.builder()
                .id(evidenceId)
                .type(EvidenceType.AST)
                .path("src/main/java/sample/UserController.java")
                .symbol("method:sample.UserController#findUsers()")
                .snippet("@GetMapping(\"/users\")")
                .build();

        ObservationFact endpoint = ObservationFact.builder()
                .kind(ObservationKind.HTTP_ENDPOINT)
                .siteSymbol("method:sample.UserController#findUsers()")
                .evidenceIds(List.of(evidenceId))
                .origin(FactOriginKind.AST)
                .confidenceHint(0.9)
                .attrs(Map.of(
                        "framework", "spring_mvc",
                        "http_methods", List.of("GET"),
                        "paths", List.of("/api/users"),
                        "path_resolution", "resolved"
                ))
                .build();

        ExtractionAggregate aggregate = ExtractionAggregate.builder()
                .evidence(Map.of(evidenceId, evidence))
                .observations(ObservationTable.builder()
                        .httpEndpoints(List.of(endpoint))
                        .build())
                .stats(StatsMeta.builder().build())
                .warnings(List.of())
                .build();

        ObservationRelationResolutionService resolutionService =
                new ObservationRelationResolutionService(
                        List.of(new EndpointObservationResolver())
                );

        DefaultFactsComposer composer = new DefaultFactsComposer(
                new FactsSectionFactory(),
                new FactsStatsCalculator(),
                resolutionService
        );

        OffsetDateTime now = OffsetDateTime.now();

        FactsCompositionContext context = new FactsCompositionContext(
                "test-schema",
                JobMeta.builder().build(),
                BuildMeta.builder().build(),
                ExtractionMode.AST_ONLY,
                now,
                now,
                List.of(),
                true,
                aggregate
        );
        FactsDocument document = composer.compose(context);

        assertNotNull(document);
        assertNotNull(document.relations());
        assertNotNull(document.relations().handlesEndpoint());
        assertEquals(1, document.relations().handlesEndpoint().size());

        var relation = document.relations().handlesEndpoint().get(0);
        assertEquals(RelationKind.HANDLES_ENDPOINT, relation.kind());
        assertEquals("GET /api/users", relation.dstRawRef());
        assertEquals(
                ResolutionStatus.RESOLVED,
                relation.resolution().status()
        );
        assertEquals(List.of(evidenceId), relation.evidenceIds());

        assertEquals(1, document.stats().relations());
        assertEquals(1, document.stats().observations());
        assertTrue(document.extraction().warnings().isEmpty());

        ObjectMapper objectMapper = new ObjectMapper()
                .findAndRegisterModules();
        JsonNode json = objectMapper.valueToTree(document);

        JsonNode handlesEndpoint = json.path("relations")
                .path("handles_endpoint");

        assertTrue(handlesEndpoint.isArray());
        assertEquals(1, handlesEndpoint.size());
        assertEquals(
                "GET /api/users",
                handlesEndpoint.get(0)
                        .path("dst_raw_ref")
                        .asText()
        );
    }
}
