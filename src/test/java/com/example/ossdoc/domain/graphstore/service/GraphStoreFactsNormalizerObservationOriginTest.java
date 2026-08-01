package com.example.ossdoc.domain.graphstore.service;

import com.example.ossdoc.domain.graphstore.dto.facts.raw.RawFactsDocumentDto;
import com.example.ossdoc.domain.graphstore.dto.facts.raw.RawObservationFactDto;
import com.example.ossdoc.domain.graphstore.dto.facts.raw.RawObservationTableDto;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedFactsDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GraphStoreFactsNormalizerObservationOriginTest {

    private final GraphStoreFactsNormalizer normalizer =
            new GraphStoreFactsNormalizer();

    @Test
    @DisplayName("Observation origin을 shadow 후보 생성 단계까지 보존한다")
    void preservesObservationOrigin() {
        RawObservationFactDto observation =
                new RawObservationFactDto();

        observation.setKind("event_publication");
        observation.setSiteSymbol(
                "method:sample.Service#publish()"
        );
        observation.setOrigin("bytecode");

        RawObservationTableDto table =
                new RawObservationTableDto();

        table.setEventPublications(
                List.of(observation)
        );

        RawFactsDocumentDto raw =
                new RawFactsDocumentDto();

        raw.setObservations(table);

        NormalizedFactsDocument normalized =
                normalizer.normalize(raw);

        assertEquals(
                "bytecode",
                normalized.observations()
                        .get(0)
                        .origin()
        );
    }
}
