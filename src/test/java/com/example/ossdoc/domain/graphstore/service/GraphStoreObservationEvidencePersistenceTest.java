package com.example.ossdoc.domain.graphstore.service;

import com.example.ossdoc.domain.artifact.repository.ArtifactRepository;
import com.example.ossdoc.domain.graphstore.converter.FactsEdgeConverter;
import com.example.ossdoc.domain.graphstore.converter.FactsEvidenceConverter;
import com.example.ossdoc.domain.graphstore.converter.FactsSymbolConverter;
import com.example.ossdoc.domain.graphstore.entity.Evidence;
import com.example.ossdoc.domain.graphstore.entity.Observation;
import com.example.ossdoc.domain.graphstore.entity.ObservationEvidence;
import com.example.ossdoc.domain.graphstore.enums.EvidenceType;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedFactsDocument;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedObservationFact;
import com.example.ossdoc.domain.graphstore.repository.EdgeEvidenceRepository;
import com.example.ossdoc.domain.graphstore.repository.EdgeRepository;
import com.example.ossdoc.domain.graphstore.repository.EvidenceRepository;
import com.example.ossdoc.domain.graphstore.repository.ObservationEvidenceRepository;
import com.example.ossdoc.domain.graphstore.repository.ObservationRepository;
import com.example.ossdoc.domain.graphstore.repository.SymbolEvidenceRepository;
import com.example.ossdoc.domain.graphstore.repository.SymbolRepository;
import com.example.ossdoc.domain.module.repository.FileIndexRepository;
import com.example.ossdoc.domain.module.repository.ModuleRepository;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.repository.RepoRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GraphStoreObservationEvidencePersistenceTest {

    @Test
    @DisplayName("재적재 시 연결을 먼저 삭제하고 Observation 저장 후 다중 Evidence를 연결한다")
    void persistsObservationEvidenceAfterDeletingOldLinks()
            throws Exception {
        ObservationRepository observationRepository =
                mock(ObservationRepository.class);

        ObservationEvidenceRepository
                observationEvidenceRepository =
                mock(ObservationEvidenceRepository.class);

        GraphStoreIngestService service =
                service(
                        observationRepository,
                        observationEvidenceRepository
                );

        when(observationRepository.saveAll(any()))
                .thenAnswer(invocation -> {
                    Iterable<?> iterable =
                            invocation.getArgument(0);

                    long nextId = 100L;

                    for (Object value : iterable) {
                        Observation observation =
                                (Observation) value;

                        setObservationId(
                                observation,
                                nextId++
                        );
                    }

                    return iterable;
                });

        RepoRun run = mock(RepoRun.class);
        when(run.getRunId()).thenReturn("run-1");

        Evidence annotation =
                evidence(201L, "event-annotation");

        Evidence parameter =
                evidence(202L, "event-parameter");

        NormalizedObservationFact observationFact =
                new NormalizedObservationFact(
                        "EVENT_SUBSCRIPTION",
                        "method:sample.Listener#handle(sample.Event)",
                        null,
                        null,
                        "event subscriber method",
                        null,
                        null,
                        List.of(
                                "event-annotation",
                                "event-parameter"
                        )
                );

        NormalizedFactsDocument facts =
                new NormalizedFactsDocument(
                        "2",
                        Map.of(),
                        List.of(),
                        List.of(),
                        List.of(observationFact)
                );

        int saved = invokeSaveObservations(
                service,
                run,
                facts,
                Map.of(
                        "event-annotation",
                        annotation,
                        "event-parameter",
                        parameter
                )
        );

        assertEquals(1, saved);

        InOrder deletionOrder = inOrder(
                observationEvidenceRepository,
                observationRepository
        );

        deletionOrder.verify(
                observationEvidenceRepository
        ).deleteAllByRunId("run-1");

        deletionOrder.verify(
                observationRepository
        ).deleteAllByRunId("run-1");

        ArgumentCaptor<Iterable<ObservationEvidence>>
                linksCaptor =
                iterableCaptor();

        verify(observationEvidenceRepository)
                .saveAll(linksCaptor.capture());

        List<ObservationEvidence> links =
                toList(linksCaptor.getValue());

        assertEquals(2, links.size());
        assertEquals(0, links.get(0).getEvidenceOrder());
        assertEquals(1, links.get(1).getEvidenceOrder());
        assertSame(annotation, links.get(0).getEvidence());
        assertSame(parameter, links.get(1).getEvidence());
        assertEquals(
                links.get(0)
                        .getObservation()
                        .getObservationId(),
                links.get(1)
                        .getObservation()
                        .getObservationId()
        );
    }

    @Test
    @DisplayName("새 Observation이 없어도 기존 연결과 Observation을 삭제한다")
    void clearsStaleObservationsWhenFactsAreEmpty()
            throws Exception {
        ObservationRepository observationRepository =
                mock(ObservationRepository.class);

        ObservationEvidenceRepository
                observationEvidenceRepository =
                mock(ObservationEvidenceRepository.class);

        GraphStoreIngestService service =
                service(
                        observationRepository,
                        observationEvidenceRepository
                );

        RepoRun run = mock(RepoRun.class);
        when(run.getRunId()).thenReturn("run-empty");

        NormalizedFactsDocument facts =
                new NormalizedFactsDocument(
                        "2",
                        Map.of(),
                        List.of(),
                        List.of(),
                        List.of()
                );

        int saved = invokeSaveObservations(
                service,
                run,
                facts,
                Map.of()
        );

        assertEquals(0, saved);

        InOrder deletionOrder = inOrder(
                observationEvidenceRepository,
                observationRepository
        );

        deletionOrder.verify(
                observationEvidenceRepository
        ).deleteAllByRunId("run-empty");

        deletionOrder.verify(
                observationRepository
        ).deleteAllByRunId("run-empty");
    }

    private GraphStoreIngestService service(
            ObservationRepository observationRepository,
            ObservationEvidenceRepository
                    observationEvidenceRepository
    ) {
        return new GraphStoreIngestService(
                mock(RepoRunRepository.class),
                mock(ArtifactRepository.class),
                mock(SymbolRepository.class),
                mock(EdgeRepository.class),
                mock(EvidenceRepository.class),
                mock(EdgeEvidenceRepository.class),
                observationRepository,
                observationEvidenceRepository,
                mock(SymbolEvidenceRepository.class),
                mock(FileIndexRepository.class),
                mock(ModuleRepository.class),
                mock(FactsEvidenceConverter.class),
                mock(FactsSymbolConverter.class),
                mock(FactsEdgeConverter.class),
                mock(SymbolIdGenerator.class),
                mock(GraphStoreFactsNormalizer.class),
                new ObjectMapper()
        );
    }

    private int invokeSaveObservations(
            GraphStoreIngestService service,
            RepoRun run,
            NormalizedFactsDocument facts,
            Map<String, Evidence> evidenceMap
    ) throws Exception {
        Method method =
                GraphStoreIngestService.class
                        .getDeclaredMethod(
                                "saveObservations",
                                RepoRun.class,
                                NormalizedFactsDocument.class,
                                Map.class
                        );

        method.setAccessible(true);

        return (int) method.invoke(
                service,
                run,
                facts,
                evidenceMap
        );
    }

    private void setObservationId(
            Observation observation,
            Long id
    ) throws Exception {
        Field field =
                Observation.class
                        .getDeclaredField(
                                "observationId"
                        );

        field.setAccessible(true);
        field.set(observation, id);
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

    @SuppressWarnings({
            "unchecked",
            "rawtypes"
    })
    private ArgumentCaptor<Iterable<ObservationEvidence>>
    iterableCaptor() {
        return (ArgumentCaptor) ArgumentCaptor
                .forClass(Iterable.class);
    }

    private List<ObservationEvidence> toList(
            Iterable<ObservationEvidence> iterable
    ) {
        java.util.ArrayList<ObservationEvidence> result =
                new java.util.ArrayList<>();

        iterable.forEach(result::add);
        return List.copyOf(result);
    }
}
