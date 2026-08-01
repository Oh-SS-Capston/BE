package com.example.ossdoc.domain.graphstore.service.promotion;

import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedFactsDocument;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedObservationFact;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedRelationFact;
import com.example.ossdoc.domain.graphstore.model.promotion.ObservationPromotionCandidateGenerationResult;
import com.example.ossdoc.domain.graphstore.model.promotion.ObservationPromotionCandidateParityReport;
import com.example.ossdoc.domain.graphstore.model.promotion.ObservationPromotionCandidateParityStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndpointEventSpiShadowParityAnalyzerTest {

    private final ObjectMapper objectMapper =
            new ObjectMapper()
                    .findAndRegisterModules();

    @Test
    @DisplayName("동일 Observation으로 생성한 Relation은 exact parity MATCHED다")
    void exactCandidateMatchesExtractionRelation() {
        NormalizedObservationFact observation =
                eventObservation();

        NormalizedFactsDocument sourceFacts =
                facts(
                        List.of(observation),
                        List.of()
                );

        ObservationPromotionCandidateGenerationResult generated =
                EndpointEventSpiShadowCandidateGenerator
                        .generate(
                                sourceFacts,
                                objectMapper
                        );

        NormalizedRelationFact extractionRelation =
                generated.candidates()
                        .get(0)
                        .relation();

        NormalizedFactsDocument comparisonFacts =
                facts(
                        List.of(observation),
                        List.of(extractionRelation)
                );

        ObservationPromotionCandidateParityReport report =
                EndpointEventSpiShadowParityAnalyzer.compare(
                        comparisonFacts,
                        generated,
                        objectMapper
                );

        assertEquals(1, report.generatedCandidateCount());
        assertEquals(1, report.extractionRelationCount());
        assertEquals(1, report.matchedCount());
        assertFalse(report.hasMismatches());
    }

    @Test
    @DisplayName("relation key가 없으면 MISSING_EXTRACTION_RELATION이다")
    void detectsMissingExtractionRelation() {
        NormalizedObservationFact observation =
                eventObservation();

        NormalizedFactsDocument facts =
                facts(
                        List.of(observation),
                        List.of()
                );

        ObservationPromotionCandidateGenerationResult generated =
                EndpointEventSpiShadowCandidateGenerator
                        .generate(
                                facts,
                                objectMapper
                        );

        ObservationPromotionCandidateParityReport report =
                EndpointEventSpiShadowParityAnalyzer.compare(
                        facts,
                        generated,
                        objectMapper
                );

        assertEquals(
                1L,
                report.counts().get(
                        ObservationPromotionCandidateParityStatus
                                .MISSING_EXTRACTION_RELATION
                )
        );
        assertTrue(report.hasMismatches());
    }

    @Test
    @DisplayName("같은 relation key라도 Evidence·attrs가 다르면 METADATA_MISMATCH다")
    void detectsMetadataMismatch() {
        NormalizedObservationFact observation =
                eventObservation();

        NormalizedFactsDocument sourceFacts =
                facts(
                        List.of(observation),
                        List.of()
                );

        ObservationPromotionCandidateGenerationResult generated =
                EndpointEventSpiShadowCandidateGenerator
                        .generate(
                                sourceFacts,
                                objectMapper
                        );

        NormalizedRelationFact candidate =
                generated.candidates()
                        .get(0)
                        .relation();

        Map<String, Object> changedAttrs =
                new java.util.LinkedHashMap<>(
                        candidate.attrs()
                );

        changedAttrs.put(
                "resolver",
                "DifferentResolver"
        );

        NormalizedRelationFact changed =
                new NormalizedRelationFact(
                        candidate.kind(),
                        candidate.srcSymbol(),
                        candidate.dstSymbol(),
                        candidate.dstRawRef(),
                        candidate.origin(),
                        candidate.derivation(),
                        candidate.resolutionStatus(),
                        candidate.resolutionReason(),
                        candidate.callSiteLine(),
                        candidate.confidenceHint(),
                        changedAttrs,
                        List.of("different-evidence")
                );

        ObservationPromotionCandidateParityReport report =
                EndpointEventSpiShadowParityAnalyzer.compare(
                        facts(
                                List.of(observation),
                                List.of(changed)
                        ),
                        generated,
                        objectMapper
                );

        assertEquals(
                ObservationPromotionCandidateParityStatus
                        .METADATA_MISMATCH,
                report.issues().get(0).status()
        );

        assertTrue(
                report.issues().get(0)
                        .reasons().stream()
                        .anyMatch(reason ->
                                reason.startsWith("evidence_ids")
                        )
        );

        assertTrue(
                report.issues().get(0)
                        .reasons().stream()
                        .anyMatch(reason ->
                                reason.startsWith("attrs")
                        )
        );
    }

    @Test
    @DisplayName("GraphStore 후보에 없는 Extraction 대상 Relation은 EXTRACTION_ONLY다")
    void detectsExtractionOnlyRelation() {
        NormalizedRelationFact extractionOnly =
                new NormalizedRelationFact(
                        "loads_service",
                        "method:sample.Loader#load()",
                        "type:sample.Plugin",
                        null,
                        "bytecode",
                        "derived",
                        "resolved",
                        "fixture",
                        null,
                        new BigDecimal("0.9"),
                        Map.of(
                                "semantic_kind", "spi_service_load",
                                "resolver", "SpiObservationResolver",
                                "resolution_basis", "exact_symbol",
                                "confidence_band", "high",
                                "default_visible", true
                        ),
                        List.of("loader")
                );

        NormalizedFactsDocument facts =
                facts(
                        List.of(),
                        List.of(extractionOnly)
                );

        ObservationPromotionCandidateParityReport report =
                EndpointEventSpiShadowParityAnalyzer.compare(
                        facts,
                        new ObservationPromotionCandidateGenerationResult(
                                0,
                                List.of(),
                                List.of()
                        ),
                        objectMapper
                );

        assertEquals(
                1L,
                report.counts().get(
                        ObservationPromotionCandidateParityStatus
                                .EXTRACTION_ONLY
                )
        );
    }

    @Test
    @DisplayName("구조 Relation과 다른 의미 Relation은 10-3-3A parity 대상에서 제외한다")
    void ignoresRelationsOutsideStageScope() {
        List<NormalizedRelationFact> unrelated =
                new ArrayList<>();

        unrelated.add(new NormalizedRelationFact(
                "calls",
                "method:sample.A#a()",
                "method:sample.B#b()",
                null,
                "ast",
                "direct",
                "resolved",
                null,
                10,
                new BigDecimal("1.0"),
                Map.of(),
                List.of("call")
        ));

        unrelated.add(new NormalizedRelationFact(
                "injects",
                "type:sample.A",
                "type:sample.B",
                null,
                "ast",
                "derived",
                "resolved",
                null,
                null,
                new BigDecimal("0.9"),
                Map.of(),
                List.of("inject")
        ));

        ObservationPromotionCandidateParityReport report =
                EndpointEventSpiShadowParityAnalyzer.compare(
                        facts(
                                List.of(),
                                unrelated
                        ),
                        new ObservationPromotionCandidateGenerationResult(
                                0,
                                List.of(),
                                List.of()
                        ),
                        objectMapper
                );

        assertEquals(0, report.extractionRelationCount());
        assertTrue(report.issues().isEmpty());
    }

    private NormalizedObservationFact eventObservation() {
        return new NormalizedObservationFact(
                "event_publication",
                "method:sample.Service#create()",
                "type:sample.CreatedEvent",
                JsonNodeFactory.instance.objectNode()
                        .put("raw", "sample.CreatedEvent")
                        .put("unresolved", false),
                null,
                "observed",
                new BigDecimal("0.9"),
                null,
                List.of("publish-call")
        );
    }

    private NormalizedFactsDocument facts(
            List<NormalizedObservationFact> observations,
            List<NormalizedRelationFact> relations
    ) {
        return new NormalizedFactsDocument(
                "2",
                Map.of(),
                List.of(),
                relations,
                observations
        );
    }
}
