package com.example.ossdoc.domain.graphstore.service;

import com.example.ossdoc.domain.graphstore.entity.Evidence;
import com.example.ossdoc.domain.graphstore.entity.Observation;
import com.example.ossdoc.domain.graphstore.entity.ObservationEvidence;
import com.example.ossdoc.domain.graphstore.entity.ObservationEvidenceId;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedObservationFact;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 저장된 Observation과 facts.json의 evidenceIds를
 * ObservationEvidence 연결 Entity로 변환한다.
 *
 * Observation Entity와 NormalizedObservationFact는
 * GraphStoreIngestService에서 동일한 순서로 생성되므로
 * 같은 index를 하나의 저장 단위로 사용한다.
 */
final class ObservationEvidenceLinkSupport {

    private ObservationEvidenceLinkSupport() {
    }

    static LinkBuildResult build(
            List<Observation> savedObservations,
            List<NormalizedObservationFact> facts,
            Map<String, Evidence> evidenceMap
    ) {
        List<Observation> safeObservations =
                savedObservations == null
                        ? List.of()
                        : savedObservations;

        List<NormalizedObservationFact> safeFacts =
                facts == null
                        ? List.of()
                        : facts;

        if (safeObservations.size() != safeFacts.size()) {
            throw new IllegalArgumentException(
                    "Saved observation count and fact count must match. "
                            + "saved="
                            + safeObservations.size()
                            + ", facts="
                            + safeFacts.size()
            );
        }

        if (safeObservations.isEmpty()) {
            return new LinkBuildResult(
                    List.of(),
                    0,
                    0
            );
        }

        Map<String, Evidence> safeEvidenceMap =
                evidenceMap == null
                        ? Map.of()
                        : evidenceMap;

        List<ObservationEvidence> links =
                new ArrayList<>();

        int missingEvidenceReferences = 0;
        int duplicateEvidenceReferences = 0;

        for (int observationIndex = 0;
             observationIndex < safeObservations.size();
             observationIndex++) {

            Observation observation =
                    safeObservations.get(observationIndex);

            NormalizedObservationFact fact =
                    safeFacts.get(observationIndex);

            if (observation == null
                    || observation.getObservationId() == null) {
                throw new IllegalStateException(
                        "Observation must have a generated ID "
                                + "before evidence linking. index="
                                + observationIndex
                );
            }

            List<String> rawEvidenceIds =
                    fact == null
                            || fact.evidenceIds() == null
                            ? List.of()
                            : fact.evidenceIds();

            Set<Long> linkedEvidenceIds =
                    new LinkedHashSet<>();

            int evidenceOrder = 0;

            for (String rawEvidenceId : rawEvidenceIds) {
                if (rawEvidenceId == null
                        || rawEvidenceId.isBlank()) {
                    missingEvidenceReferences++;
                    continue;
                }

                Evidence evidence =
                        safeEvidenceMap.get(rawEvidenceId);

                if (evidence == null
                        || evidence.getEvidenceId() == null) {
                    missingEvidenceReferences++;
                    continue;
                }

                Long evidenceId =
                        evidence.getEvidenceId();

                if (!linkedEvidenceIds.add(evidenceId)) {
                    duplicateEvidenceReferences++;
                    continue;
                }

                ObservationEvidenceId id =
                        new ObservationEvidenceId(
                                observation.getObservationId(),
                                evidenceId
                        );

                links.add(new ObservationEvidence(
                        id,
                        observation,
                        evidence,
                        evidenceOrder++
                ));
            }
        }

        return new LinkBuildResult(
                List.copyOf(links),
                missingEvidenceReferences,
                duplicateEvidenceReferences
        );
    }

    record LinkBuildResult(
            List<ObservationEvidence> links,
            int missingEvidenceReferences,
            int duplicateEvidenceReferences
    ) {
    }
}
