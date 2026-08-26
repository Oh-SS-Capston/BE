package com.example.ossdoc.domain.extraction.service.support.resolve;

import com.example.ossdoc.domain.extraction.dto.model.RelationFact;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 하나의 Observation resolver 또는 resolver 체인의 실행 결과.
 */
public record ObservationResolutionResult(
        List<RelationFact> relations,
        List<String> warnings
) {

    public ObservationResolutionResult {
        relations = sanitizeRelations(relations);
        warnings = sanitizeWarnings(warnings);
    }

    public static ObservationResolutionResult empty() {
        return new ObservationResolutionResult(
                List.of(),
                List.of()
        );
    }

    public static ObservationResolutionResult ofRelations(
            List<RelationFact> relations
    ) {
        return new ObservationResolutionResult(
                relations,
                List.of()
        );
    }

    public ObservationResolutionResult merge(
            ObservationResolutionResult other
    ) {
        if (other == null) {
            return this;
        }

        List<RelationFact> mergedRelations =
                new ArrayList<>(relations);
        mergedRelations.addAll(other.relations());

        LinkedHashSet<String> mergedWarnings =
                new LinkedHashSet<>(warnings);
        mergedWarnings.addAll(other.warnings());

        return new ObservationResolutionResult(
                mergedRelations,
                List.copyOf(mergedWarnings)
        );
    }

    private static List<RelationFact> sanitizeRelations(
            List<RelationFact> source
    ) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }

        return source.stream()
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private static List<String> sanitizeWarnings(
            List<String> source
    ) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<String> result =
                new LinkedHashSet<>();

        for (String warning : source) {
            if (warning == null || warning.isBlank()) {
                continue;
            }
            result.add(warning);
        }

        return List.copyOf(result);
    }
}
