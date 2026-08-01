package com.example.ossdoc.domain.graphstore.service;

import com.example.ossdoc.domain.graphstore.entity.Evidence;
import com.example.ossdoc.domain.graphstore.entity.Observation;
import com.example.ossdoc.domain.graphstore.entity.ObservationEvidence;
import com.example.ossdoc.domain.graphstore.enums.EvidenceType;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedObservationFact;
import com.example.ossdoc.domain.run.entity.RepoRun;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class ObservationEvidenceLinkSupportTest {

    @Test
    @DisplayName("Observation의 다중 Evidence를 원본 순서로 연결하고 중복·누락을 집계한다")
    void buildsOrderedDistinctEvidenceLinks() {
        Observation observation =
                observation(101L);

        Evidence annotation =
                evidence(201L, "ast-event-annotation");

        Evidence parameter =
                evidence(202L, "ast-event-parameter");

        NormalizedObservationFact fact =
                new NormalizedObservationFact(
                        "EVENT_SUBSCRIPTION",
                        "method:sample.Listener#handle(sample.Event)",
                        null,
                        null,
                        "event subscriber method",
                        null,
                        null,
                        List.of(
                                "ast-event-annotation",
                                "ast-event-parameter",
                                "ast-event-annotation",
                                "missing-evidence"
                        )
                );

        ObservationEvidenceLinkSupport.LinkBuildResult
                result =
                ObservationEvidenceLinkSupport.build(
                        List.of(observation),
                        List.of(fact),
                        Map.of(
                                "ast-event-annotation",
                                annotation,
                                "ast-event-parameter",
                                parameter
                        )
                );

        assertEquals(2, result.links().size());
        assertEquals(
                1,
                result.missingEvidenceReferences()
        );
        assertEquals(
                1,
                result.duplicateEvidenceReferences()
        );

        ObservationEvidence first =
                result.links().get(0);

        ObservationEvidence second =
                result.links().get(1);

        assertEquals(0, first.getEvidenceOrder());
        assertEquals(1, second.getEvidenceOrder());

        assertSame(observation, first.getObservation());
        assertSame(annotation, first.getEvidence());
        assertSame(parameter, second.getEvidence());

        assertEquals(
                101L,
                first.getId().getObservationId()
        );
        assertEquals(
                201L,
                first.getId().getEvidenceId()
        );
        assertEquals(
                202L,
                second.getId().getEvidenceId()
        );
    }

    @Test
    @DisplayName("저장 Observation 수와 정규화 Fact 수가 다르면 즉시 실패한다")
    void rejectsMismatchedObservationAndFactCounts() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                ObservationEvidenceLinkSupport
                                        .build(
                                                List.of(
                                                        observation(1L)
                                                ),
                                                List.of(),
                                                Map.of()
                                        )
                );

        assertEquals(
                "Saved observation count and fact count must match. "
                        + "saved=1, facts=0",
                exception.getMessage()
        );
    }

    @Test
    @DisplayName("Observation ID 생성 전에는 Evidence 연결을 만들지 않는다")
    void requiresGeneratedObservationId() {
        Observation unsaved =
                observation(null);

        NormalizedObservationFact fact =
                new NormalizedObservationFact(
                        "EVENT_SUBSCRIPTION",
                        "method:sample.Listener#handle(sample.Event)",
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of()
                );

        assertThrows(
                IllegalStateException.class,
                () ->
                        ObservationEvidenceLinkSupport
                                .build(
                                        List.of(unsaved),
                                        List.of(fact),
                                        Map.of()
                                )
        );
    }

    private Observation observation(Long id) {
        return new Observation(
                id,
                mock(RepoRun.class),
                "EVENT_SUBSCRIPTION",
                "method:sample.Listener#handle(sample.Event)",
                null,
                null,
                "event subscriber method",
                null,
                null
        );
    }

    private Evidence evidence(
            Long id,
            String rawId
    ) {
        return new Evidence(
                id,
                mock(RepoRun.class),
                EvidenceType.AST,
                null,
                10,
                5,
                10,
                25,
                "method:sample.Listener#handle(sample.Event)",
                "@EventListener",
                "hash-" + id,
                rawId,
                null
        );
    }
}
