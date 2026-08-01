package com.example.ossdoc.domain.graphstore.service.promotion;

import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedFactsDocument;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedObservationFact;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedRelationFact;
import com.example.ossdoc.domain.graphstore.model.promotion.ObservationPromotionShadowIssue;
import com.example.ossdoc.domain.graphstore.model.promotion.ObservationPromotionShadowReport;
import com.example.ossdoc.domain.graphstore.model.promotion.ObservationPromotionShadowStatus;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObservationPromotionShadowAnalyzerTest {

    @Test
    @DisplayName("Event Observation과 Extraction 의미 Relation의 전체 계약이 일치한다")
    void matchedEventPromotion() {
        NormalizedObservationFact observation =
                observation(
                        "event_publication",
                        "method:sample.Service#create()",
                        "type:sample.CreatedEvent",
                        List.of("event-call")
                );

        NormalizedRelationFact relation =
                relation(
                        "publishes_event",
                        "method:sample.Service#create()",
                        "type:sample.CreatedEvent",
                        null,
                        "EventObservationResolver",
                        "event_publication",
                        List.of("event-call")
                );

        ObservationPromotionShadowReport report =
                analyze(
                        List.of(observation),
                        List.of(relation)
                );

        assertEquals(1, report.totalObservations());
        assertEquals(1, report.promotableObservations());
        assertEquals(1, report.matchedCount());
        assertFalse(report.hasMismatches());
        assertEquals(
                ObservationPromotionShadowStatus.MATCHED,
                report.issues().get(0).status()
        );
    }

    @Test
    @DisplayName("승격 대상 Observation에 의미 Relation이 없으면 MISSING_RELATION이다")
    void missingRelation() {
        ObservationPromotionShadowReport report =
                analyze(
                        List.of(observation(
                                "http_endpoint",
                                "method:sample.Controller#create()",
                                null,
                                List.of("endpoint-annotation")
                        )),
                        List.of()
                );

        assertEquals(
                ObservationPromotionShadowStatus.MISSING_RELATION,
                report.issues().get(0).status()
        );
        assertTrue(report.hasMismatches());
    }

    @Test
    @DisplayName("같은 Observation 근거가 계약 밖 RelationKind에 연결되면 KIND_MISMATCH다")
    void relationKindMismatch() {
        NormalizedObservationFact observation =
                observation(
                        "event_publication",
                        "method:sample.Service#create()",
                        "type:sample.CreatedEvent",
                        List.of("event-call")
                );

        NormalizedRelationFact wrongKind =
                relation(
                        "listens_event",
                        "method:sample.Service#create()",
                        "type:sample.CreatedEvent",
                        null,
                        "EventObservationResolver",
                        "event_publication",
                        List.of("event-call")
                );

        ObservationPromotionShadowIssue issue =
                analyze(
                        List.of(observation),
                        List.of(wrongKind)
                ).issues().get(0);

        assertEquals(
                ObservationPromotionShadowStatus.KIND_MISMATCH,
                issue.status()
        );
        assertEquals(
                "listens_event",
                issue.relationKind()
        );
    }

    @Test
    @DisplayName("resolver와 필수 정책 attrs가 다르면 METADATA_MISMATCH다")
    void metadataMismatch() {
        NormalizedObservationFact observation =
                observation(
                        "event_subscription",
                        "method:sample.Listener#handle(sample.Event)",
                        "type:sample.Event",
                        List.of("listener-annotation")
                );

        NormalizedRelationFact relation =
                new NormalizedRelationFact(
                        "listens_event",
                        "method:sample.Listener#handle(sample.Event)",
                        "type:sample.Event",
                        null,
                        "observed",
                        "derived",
                        "resolved",
                        "fixture",
                        null,
                        new BigDecimal("0.9"),
                        Map.of(
                                "semantic_kind", "event_subscription",
                                "resolver", "WrongResolver",
                                "resolution_basis", "target_symbol",
                                "confidence_band", "high"
                        ),
                        List.of("listener-annotation")
                );

        ObservationPromotionShadowIssue issue =
                analyze(
                        List.of(observation),
                        List.of(relation)
                ).issues().get(0);

        assertEquals(
                ObservationPromotionShadowStatus.METADATA_MISMATCH,
                issue.status()
        );
        assertTrue(
                issue.reasons().stream()
                        .anyMatch(reason ->
                                reason.contains("resolver expected")
                        )
        );
        assertTrue(
                issue.reasons().contains(
                        "required attr missing: default_visible"
                )
        );
    }

    @Test
    @DisplayName("Relation이 원본 Observation Evidence를 승계하지 않으면 EVIDENCE_MISMATCH다")
    void evidenceMismatch() {
        NormalizedObservationFact observation =
                observation(
                        "config_wiring",
                        "method:sample.Config#configure()",
                        null,
                        List.of("config-annotation")
                );

        NormalizedRelationFact relation =
                relation(
                        "configures_bean",
                        "method:sample.Config#configure()",
                        null,
                        "bean:service",
                        "ConfigurationObservationResolver",
                        "configuration_wiring",
                        List.of("different-evidence")
                );

        ObservationPromotionShadowIssue issue =
                analyze(
                        List.of(observation),
                        List.of(relation)
                ).issues().get(0);

        assertEquals(
                ObservationPromotionShadowStatus.EVIDENCE_MISMATCH,
                issue.status()
        );
        assertTrue(
                issue.reasons().get(0)
                        .contains("config-annotation")
        );
    }

    @Test
    @DisplayName("DI Relation은 owner type을 src로 사용해도 injection_site_symbol로 매칭된다")
    void diUsesInjectionSiteAnchorAndAllowsProviderEvidence() {
        NormalizedObservationFact observation =
                observation(
                        "di_injection_site",
                        "field:sample.Controller#service",
                        "type:sample.Service",
                        List.of(
                                "inject-annotation",
                                "inject-field"
                        )
                );

        NormalizedRelationFact relation =
                new NormalizedRelationFact(
                        "injects",
                        "type:sample.Controller",
                        "type:sample.ServiceImpl",
                        null,
                        "ast",
                        "derived",
                        "resolved",
                        "qualified provider",
                        null,
                        new BigDecimal("0.95"),
                        Map.of(
                                "semantic_kind", "dependency_injection",
                                "resolver", "DiObservationResolver",
                                "resolution_basis", "qualified_candidate",
                                "confidence_band", "high",
                                "default_visible", true,
                                "injection_site_symbol",
                                "field:sample.Controller#service"
                        ),
                        List.of(
                                "inject-annotation",
                                "inject-field",
                                "provider-annotation"
                        )
                );

        ObservationPromotionShadowReport report =
                analyze(
                        List.of(observation),
                        List.of(relation)
                );

        assertEquals(1, report.matchedCount());
        assertFalse(report.hasMismatches());
    }

    @Test
    @DisplayName("SPI source_observation_kind의 enum 이름도 JSON code와 동일하게 매칭한다")
    void spiSourceObservationKindAnchor() {
        NormalizedObservationFact observation =
                observation(
                        "module_uses",
                        "module:sample.app",
                        "type:sample.Plugin",
                        List.of()
                );

        NormalizedRelationFact relation =
                new NormalizedRelationFact(
                        "loads_service",
                        "module:sample.app",
                        "type:sample.Plugin",
                        null,
                        "resource",
                        "derived",
                        "resolved",
                        "module uses",
                        null,
                        new BigDecimal("0.9"),
                        Map.of(
                                "semantic_kind", "spi_service_load",
                                "resolver", "SpiObservationResolver",
                                "resolution_basis", "target_symbol",
                                "confidence_band", "high",
                                "default_visible", true,
                                "source_observation_kind", "MODULE_USES"
                        ),
                        List.of()
                );

        ObservationPromotionShadowReport report =
                analyze(
                        List.of(observation),
                        List.of(relation)
                );

        assertEquals(1, report.matchedCount());
    }

    @Test
    @DisplayName("Resolver 계약이 없는 Observation은 NOT_PROMOTABLE로 분리한다")
    void notPromotableObservation() {
        ObservationPromotionShadowReport report =
                analyze(
                        List.of(observation(
                                "scheduled_task",
                                "method:sample.Job#run()",
                                null,
                                List.of("scheduled")
                        )),
                        List.of()
                );

        assertEquals(0, report.promotableObservations());
        assertEquals(
                ObservationPromotionShadowStatus.NOT_PROMOTABLE,
                report.issues().get(0).status()
        );
        assertFalse(report.hasMismatches());
    }

    @Test
    @DisplayName("Shadow report는 상태별 집계와 mismatch 수를 제공한다")
    void reportCountsStatuses() {
        NormalizedObservationFact matched =
                observation(
                        "event_publication",
                        "method:sample.A#publish()",
                        "type:sample.Event",
                        List.of("event-1")
                );

        NormalizedRelationFact matchedRelation =
                relation(
                        "publishes_event",
                        "method:sample.A#publish()",
                        "type:sample.Event",
                        null,
                        "EventObservationResolver",
                        "event_publication",
                        List.of("event-1")
                );

        NormalizedObservationFact missing =
                observation(
                        "http_endpoint",
                        "method:sample.Api#get()",
                        null,
                        List.of("endpoint-1")
                );

        NormalizedObservationFact ignored =
                observation(
                        "async_method",
                        "method:sample.Async#run()",
                        null,
                        List.of("async-1")
                );

        ObservationPromotionShadowReport report =
                analyze(
                        List.of(
                                matched,
                                missing,
                                ignored
                        ),
                        List.of(matchedRelation)
                );

        assertEquals(
                1L,
                report.counts().get(
                        ObservationPromotionShadowStatus.MATCHED
                )
        );
        assertEquals(
                1L,
                report.counts().get(
                        ObservationPromotionShadowStatus.MISSING_RELATION
                )
        );
        assertEquals(
                1L,
                report.counts().get(
                        ObservationPromotionShadowStatus.NOT_PROMOTABLE
                )
        );
        assertEquals(1, report.mismatchCount());
    }

    private ObservationPromotionShadowReport analyze(
            List<NormalizedObservationFact> observations,
            List<NormalizedRelationFact> relations
    ) {
        return ObservationPromotionShadowAnalyzer.analyze(
                new NormalizedFactsDocument(
                        "2",
                        Map.of(),
                        List.of(),
                        relations,
                        observations
                )
        );
    }

    private NormalizedObservationFact observation(
            String kind,
            String siteSymbol,
            String targetSymbol,
            List<String> evidenceIds
    ) {
        return new NormalizedObservationFact(
                kind,
                siteSymbol,
                targetSymbol,
                targetSymbol == null
                        ? null
                        : JsonNodeFactory.instance
                                .objectNode()
                                .put(
                                        "raw",
                                        targetSymbol.replace(
                                                "type:",
                                                ""
                                        )
                                ),
                null,
                new BigDecimal("0.9"),
                null,
                evidenceIds
        );
    }

    private NormalizedRelationFact relation(
            String kind,
            String srcSymbol,
            String dstSymbol,
            String dstRawRef,
            String resolver,
            String semanticKind,
            List<String> evidenceIds
    ) {
        return new NormalizedRelationFact(
                kind,
                srcSymbol,
                dstSymbol,
                dstRawRef,
                "observed",
                "derived",
                "resolved",
                "fixture",
                20,
                new BigDecimal("0.9"),
                Map.of(
                        "semantic_kind", semanticKind,
                        "resolver", resolver,
                        "resolution_basis", "target_symbol",
                        "confidence_band", "high",
                        "default_visible", true
                ),
                evidenceIds
        );
    }
}
