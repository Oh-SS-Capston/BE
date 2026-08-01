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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BeanConfigurationShadowParityAnalyzerTest {

    private final ObjectMapper objectMapper =
            new ObjectMapper()
                    .findAndRegisterModules();

    @Test
    @DisplayName("동일 Bean Observation으로 생성한 Relation은 exact parity MATCHED다")
    void beanCandidateMatchesExtractionRelation() {
        NormalizedObservationFact observation =
                beanObservation();

        NormalizedFactsDocument sourceFacts =
                facts(
                        List.of(observation),
                        List.of()
                );

        ObservationPromotionCandidateGenerationResult generated =
                BeanConfigurationShadowCandidateGenerator
                        .generate(
                                sourceFacts,
                                objectMapper
                        );

        NormalizedRelationFact extraction =
                generated.candidates()
                        .get(0)
                        .relation();

        ObservationPromotionCandidateParityReport report =
                BeanConfigurationShadowParityAnalyzer.compare(
                        facts(
                                List.of(observation),
                                List.of(extraction)
                        ),
                        generated,
                        objectMapper
                );

        assertEquals(1, report.matchedCount());
        assertFalse(report.hasMismatches());
    }

    @Test
    @DisplayName("동일 Configuration Observation으로 생성한 다중 Relation이 모두 MATCHED다")
    void configurationCandidatesMatchExtractionRelations() {
        var attrs =
                JsonNodeFactory.instance.objectNode();

        attrs.putArray("imported_types")
                .add("sample.FeatureConfig");

        attrs.putArray("component_scan_packages")
                .add("sample.feature");

        NormalizedObservationFact observation =
                new NormalizedObservationFact(
                        "config_wiring",
                        "type:sample.AppConfig",
                        null,
                        null,
                        null,
                        "ast",
                        new BigDecimal("0.9"),
                        attrs,
                        List.of("configuration")
                );

        NormalizedFactsDocument sourceFacts =
                facts(
                        List.of(observation),
                        List.of()
                );

        ObservationPromotionCandidateGenerationResult generated =
                BeanConfigurationShadowCandidateGenerator
                        .generate(
                                sourceFacts,
                                objectMapper
                        );

        List<NormalizedRelationFact> extraction =
                generated.candidates().stream()
                        .map(candidate ->
                                candidate.relation()
                        )
                        .toList();

        ObservationPromotionCandidateParityReport report =
                BeanConfigurationShadowParityAnalyzer.compare(
                        facts(
                                List.of(observation),
                                extraction
                        ),
                        generated,
                        objectMapper
                );

        assertEquals(2, report.generatedCandidateCount());
        assertEquals(2, report.extractionRelationCount());
        assertEquals(2, report.matchedCount());
        assertFalse(report.hasMismatches());
    }

    @Test
    @DisplayName("Bean relation key가 없으면 MISSING_EXTRACTION_RELATION이다")
    void detectsMissingBeanRelation() {
        NormalizedObservationFact observation =
                beanObservation();

        NormalizedFactsDocument facts =
                facts(
                        List.of(observation),
                        List.of()
                );

        ObservationPromotionCandidateGenerationResult generated =
                BeanConfigurationShadowCandidateGenerator
                        .generate(
                                facts,
                                objectMapper
                        );

        ObservationPromotionCandidateParityReport report =
                BeanConfigurationShadowParityAnalyzer.compare(
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
    @DisplayName("같은 key라도 name_resolution과 Evidence가 다르면 METADATA_MISMATCH다")
    void detectsBeanMetadataMismatch() {
        NormalizedObservationFact observation =
                beanObservation();

        NormalizedFactsDocument sourceFacts =
                facts(
                        List.of(observation),
                        List.of()
                );

        ObservationPromotionCandidateGenerationResult generated =
                BeanConfigurationShadowCandidateGenerator
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
                "name_resolution",
                "declared"
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
                BeanConfigurationShadowParityAnalyzer.compare(
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
                                reason.startsWith(
                                        "evidence_ids"
                                )
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
    @DisplayName("후보에 없는 Extraction Bean/Configuration Relation은 EXTRACTION_ONLY다")
    void detectsExtractionOnlyRelation() {
        NormalizedRelationFact extractionOnly =
                new NormalizedRelationFact(
                        "configures_bean",
                        "type:sample.AppConfig",
                        null,
                        "package:sample.feature",
                        "ast",
                        "derived",
                        "resolved",
                        null,
                        null,
                        new BigDecimal("0.9"),
                        Map.of(
                                "semantic_kind",
                                "configuration_wiring",
                                "resolver",
                                "ConfigurationObservationResolver",
                                "resolution_basis",
                                "raw_reference",
                                "confidence_band",
                                "high",
                                "default_visible",
                                true
                        ),
                        List.of("configuration")
                );

        ObservationPromotionCandidateParityReport report =
                BeanConfigurationShadowParityAnalyzer.compare(
                        facts(
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
    @DisplayName("다른 의미 Relation은 10-3-3B parity 대상에서 제외한다")
    void ignoresOtherRelationKinds() {
        NormalizedRelationFact unrelated =
                new NormalizedRelationFact(
                        "publishes_event",
                        "method:sample.Service#publish()",
                        "type:sample.Event",
                        null,
                        "observed",
                        "derived",
                        "resolved",
                        null,
                        null,
                        new BigDecimal("0.9"),
                        Map.of(),
                        List.of("event")
                );

        ObservationPromotionCandidateParityReport report =
                BeanConfigurationShadowParityAnalyzer.compare(
                        facts(
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

    private NormalizedObservationFact beanObservation() {
        return new NormalizedObservationFact(
                "di_provider",
                "method:sample.AppConfig#clock()",
                null,
                JsonNodeFactory.instance
                        .objectNode()
                        .put(
                                "raw",
                                "java.time.Clock"
                        ),
                null,
                "ast",
                new BigDecimal("0.9"),
                null,
                List.of("bean")
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
