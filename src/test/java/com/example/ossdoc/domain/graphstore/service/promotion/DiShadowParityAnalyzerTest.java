package com.example.ossdoc.domain.graphstore.service.promotion;

import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedFactsDocument;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedObservationFact;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedRelationFact;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedSymbolFact;
import com.example.ossdoc.domain.graphstore.model.promotion.ObservationPromotionCandidateGenerationResult;
import com.example.ossdoc.domain.graphstore.model.promotion.ObservationPromotionCandidateParityReport;
import com.example.ossdoc.domain.graphstore.model.promotion.ObservationPromotionCandidateParityStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiShadowParityAnalyzerTest {

    private final ObjectMapper objectMapper =
            new ObjectMapper().findAndRegisterModules();

    @Test
    @DisplayName("동일 DI Observation으로 생성한 INJECTS Relation은 exact parity MATCHED다")
    void exactCandidateMatchesExtractionRelation() {
        Fixture fixture = fixture();

        ObservationPromotionCandidateGenerationResult generated =
                DiShadowCandidateGenerator.generate(
                        fixture.sourceFacts(),
                        objectMapper
                );

        NormalizedRelationFact extraction =
                generated.candidates().get(0).relation();

        ObservationPromotionCandidateParityReport report =
                DiShadowParityAnalyzer.compare(
                        facts(
                                fixture.symbols(),
                                fixture.observations(),
                                List.of(extraction)
                        ),
                        generated,
                        objectMapper
                );

        assertEquals(1, report.generatedCandidateCount());
        assertEquals(1, report.extractionRelationCount());
        assertEquals(1, report.matchedCount());
        assertFalse(report.hasMismatches());
    }

    @Test
    @DisplayName("INJECTS relation key가 없으면 MISSING_EXTRACTION_RELATION이다")
    void detectsMissingExtractionRelation() {
        Fixture fixture = fixture();

        ObservationPromotionCandidateGenerationResult generated =
                DiShadowCandidateGenerator.generate(
                        fixture.sourceFacts(),
                        objectMapper
                );

        ObservationPromotionCandidateParityReport report =
                DiShadowParityAnalyzer.compare(
                        fixture.sourceFacts(),
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
    @DisplayName("같은 key라도 match_strategy와 Provider Evidence가 다르면 METADATA_MISMATCH다")
    void detectsMetadataMismatch() {
        Fixture fixture = fixture();

        ObservationPromotionCandidateGenerationResult generated =
                DiShadowCandidateGenerator.generate(
                        fixture.sourceFacts(),
                        objectMapper
                );

        NormalizedRelationFact candidate =
                generated.candidates().get(0).relation();

        Map<String, Object> changedAttrs =
                new LinkedHashMap<>(candidate.attrs());
        changedAttrs.put("match_strategy", "different_strategy");

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
                        List.of("inject-only")
                );

        ObservationPromotionCandidateParityReport report =
                DiShadowParityAnalyzer.compare(
                        facts(
                                fixture.symbols(),
                                fixture.observations(),
                                List.of(changed)
                        ),
                        generated,
                        objectMapper
                );

        assertEquals(
                ObservationPromotionCandidateParityStatus.METADATA_MISMATCH,
                report.issues().get(0).status()
        );
        assertTrue(
                report.issues().get(0).reasons().stream()
                        .anyMatch(reason ->
                                reason.startsWith("evidence_ids"))
        );
        assertTrue(
                report.issues().get(0).reasons().stream()
                        .anyMatch(reason ->
                                reason.startsWith("attrs"))
        );
    }

    @Test
    @DisplayName("GraphStore 후보에 없는 Extraction INJECTS Relation은 EXTRACTION_ONLY다")
    void detectsExtractionOnlyRelation() {
        NormalizedRelationFact extractionOnly =
                new NormalizedRelationFact(
                        "injects",
                        "type:sample.Controller",
                        "type:sample.Service",
                        null,
                        "ast",
                        "derived",
                        "resolved",
                        null,
                        null,
                        new BigDecimal("0.9"),
                        Map.of(
                                "semantic_kind", "dependency_injection",
                                "resolver", "DiObservationResolver"
                        ),
                        List.of("inject")
                );

        ObservationPromotionCandidateParityReport report =
                DiShadowParityAnalyzer.compare(
                        facts(
                                List.of(),
                                List.of(),
                                List.of(extractionOnly)
                        ),
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
    @DisplayName("다른 의미 Relation은 DI parity 대상에서 제외한다")
    void ignoresOtherRelationKinds() {
        NormalizedRelationFact unrelated =
                new NormalizedRelationFact(
                        "declares_bean",
                        "method:sample.Config#service()",
                        null,
                        "bean:service",
                        "ast",
                        "derived",
                        "resolved",
                        null,
                        null,
                        new BigDecimal("0.9"),
                        Map.of(),
                        List.of()
                );

        ObservationPromotionCandidateParityReport report =
                DiShadowParityAnalyzer.compare(
                        facts(
                                List.of(),
                                List.of(),
                                List.of(unrelated)
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

    private Fixture fixture() {
        ObjectNode injectionType =
                JsonNodeFactory.instance.objectNode();
        injectionType.put("raw", "sample.Service");
        injectionType.put("unresolved", false);

        NormalizedObservationFact injection =
                new NormalizedObservationFact(
                        "di_injection_site",
                        "field:sample.Controller#service",
                        null,
                        injectionType,
                        "field injection",
                        "ast",
                        new BigDecimal("0.9"),
                        null,
                        List.of(
                                "inject-annotation",
                                "inject-field"
                        )
                );

        ObjectNode providerType =
                JsonNodeFactory.instance.objectNode();
        providerType.put("raw", "sample.Service");

        ObjectNode providerAttrs =
                JsonNodeFactory.instance.objectNode();
        providerAttrs.put(
                "provided_type",
                "sample.Service"
        );

        NormalizedObservationFact provider =
                new NormalizedObservationFact(
                        "di_provider",
                        "type:sample.ServiceImpl",
                        null,
                        providerType,
                        null,
                        "bytecode",
                        new BigDecimal("0.9"),
                        providerAttrs,
                        List.of("provider")
                );

        NormalizedSymbolFact field = symbol(
                "field:sample.Controller#service",
                "service",
                "field",
                "type:sample.Controller"
        );

        List<NormalizedSymbolFact> symbols = List.of(field);
        List<NormalizedObservationFact> observations =
                List.of(injection, provider);

        return new Fixture(
                symbols,
                observations,
                facts(symbols, observations, List.of())
        );
    }

    private NormalizedFactsDocument facts(
            List<NormalizedSymbolFact> symbols,
            List<NormalizedObservationFact> observations,
            List<NormalizedRelationFact> relations
    ) {
        return new NormalizedFactsDocument(
                "2",
                Map.of(),
                symbols,
                relations,
                observations
        );
    }

    private NormalizedSymbolFact symbol(
            String symbol,
            String name,
            String kind,
            String ownerTypeSymbol
    ) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("symbol", symbol);
        node.put("name", name);
        node.put("kind", kind);
        node.put("ownerTypeSymbol", ownerTypeSymbol);

        return objectMapper.convertValue(
                node,
                NormalizedSymbolFact.class
        );
    }

    private record Fixture(
            List<NormalizedSymbolFact> symbols,
            List<NormalizedObservationFact> observations,
            NormalizedFactsDocument sourceFacts
    ) {
    }
}
