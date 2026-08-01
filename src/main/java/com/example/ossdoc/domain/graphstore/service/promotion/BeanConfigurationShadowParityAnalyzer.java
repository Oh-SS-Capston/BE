package com.example.ossdoc.domain.graphstore.service.promotion;

import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedFactsDocument;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedRelationFact;
import com.example.ossdoc.domain.graphstore.model.promotion.ObservationPromotionCandidateGenerationResult;
import com.example.ossdoc.domain.graphstore.model.promotion.ObservationPromotionCandidateParityIssue;
import com.example.ossdoc.domain.graphstore.model.promotion.ObservationPromotionCandidateParityReport;
import com.example.ossdoc.domain.graphstore.model.promotion.ObservationPromotionCandidateParityStatus;
import com.example.ossdoc.domain.graphstore.model.promotion.ObservationPromotionShadowCandidate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Bean·Configuration GraphStore shadow 후보와 Extraction Relation을
 * relation identity 및 metadata 단위로 exact 비교한다.
 */
public final class BeanConfigurationShadowParityAnalyzer {

    private static final Set<String> TARGET_RELATION_KINDS =
            Set.of(
                    "declares_bean",
                    "configures_bean"
            );

    private BeanConfigurationShadowParityAnalyzer() {
    }

    public static ObservationPromotionCandidateParityReport compare(
            NormalizedFactsDocument facts,
            ObservationPromotionCandidateGenerationResult generated,
            ObjectMapper objectMapper
    ) {
        ObjectMapper mapper =
                objectMapper == null
                        ? new ObjectMapper()
                                .findAndRegisterModules()
                        : objectMapper;

        List<NormalizedRelationFact> allRelations =
                facts == null
                        || facts.relations() == null
                        ? List.of()
                        : facts.relations();

        Map<String, NormalizedRelationFact> extractionByKey =
                new LinkedHashMap<>();

        for (NormalizedRelationFact relation : allRelations) {
            if (relation == null
                    || !TARGET_RELATION_KINDS.contains(
                            normalizeCode(
                                    relation.kind()
                            )
                    )) {
                continue;
            }

            extractionByKey.put(
                    ObservationPromotionShadowCandidate
                            .relationKey(relation),
                    relation
            );
        }

        List<ObservationPromotionShadowCandidate> candidates =
                generated == null
                        ? List.of()
                        : generated.candidates();

        List<ObservationPromotionCandidateParityIssue> issues =
                new ArrayList<>();

        for (ObservationPromotionShadowCandidate candidate
                : candidates) {
            String key =
                    candidate.relationKey();

            NormalizedRelationFact extraction =
                    extractionByKey.remove(key);

            if (extraction == null) {
                issues.add(
                        new ObservationPromotionCandidateParityIssue(
                                key,
                                ObservationPromotionCandidateParityStatus
                                        .MISSING_EXTRACTION_RELATION,
                                candidate.observationIndex(),
                                candidate.observationKind(),
                                List.of(
                                        "No Extraction relation exists "
                                                + "for the generated relation key"
                                )
                        )
                );
                continue;
            }

            List<String> reasons =
                    metadataDifferences(
                            candidate.relation(),
                            extraction,
                            mapper
                    );

            issues.add(
                    new ObservationPromotionCandidateParityIssue(
                            key,
                            reasons.isEmpty()
                                    ? ObservationPromotionCandidateParityStatus
                                            .MATCHED
                                    : ObservationPromotionCandidateParityStatus
                                            .METADATA_MISMATCH,
                            candidate.observationIndex(),
                            candidate.observationKind(),
                            reasons
                    )
            );
        }

        for (Map.Entry<String, NormalizedRelationFact> entry
                : extractionByKey.entrySet()) {
            issues.add(
                    new ObservationPromotionCandidateParityIssue(
                            entry.getKey(),
                            ObservationPromotionCandidateParityStatus
                                    .EXTRACTION_ONLY,
                            null,
                            null,
                            List.of(
                                    "Extraction produced a Bean/Configuration "
                                            + "relation that GraphStore shadow "
                                            + "generation did not reproduce"
                            )
                    )
            );
        }

        int extractionRelationCount =
                (int) allRelations.stream()
                        .filter(Objects::nonNull)
                        .filter(relation ->
                                TARGET_RELATION_KINDS.contains(
                                        normalizeCode(
                                                relation.kind()
                                        )
                                )
                        )
                        .map(
                                ObservationPromotionShadowCandidate
                                        ::relationKey
                        )
                        .distinct()
                        .count();

        return new ObservationPromotionCandidateParityReport(
                candidates.size(),
                extractionRelationCount,
                issues
        );
    }

    private static List<String> metadataDifferences(
            NormalizedRelationFact generated,
            NormalizedRelationFact extraction,
            ObjectMapper mapper
    ) {
        List<String> reasons =
                new ArrayList<>();

        compare(
                reasons,
                "origin",
                normalizeCode(generated.origin()),
                normalizeCode(extraction.origin())
        );

        compare(
                reasons,
                "derivation",
                normalizeCode(generated.derivation()),
                normalizeCode(extraction.derivation())
        );

        compare(
                reasons,
                "resolution.status",
                normalizeCode(
                        generated.resolutionStatus()
                ),
                normalizeCode(
                        extraction.resolutionStatus()
                )
        );

        compare(
                reasons,
                "resolution.reason",
                generated.resolutionReason(),
                extraction.resolutionReason()
        );

        compare(
                reasons,
                "call_site_line",
                generated.callSiteLine(),
                extraction.callSiteLine()
        );

        if (!sameDecimal(
                generated.confidenceHint(),
                extraction.confidenceHint()
        )) {
            reasons.add(
                    "confidence_hint expected="
                            + generated.confidenceHint()
                            + ", actual="
                            + extraction.confidenceHint()
            );
        }

        if (!Objects.equals(
                generated.evidenceIds(),
                extraction.evidenceIds()
        )) {
            reasons.add(
                    "evidence_ids expected="
                            + generated.evidenceIds()
                            + ", actual="
                            + extraction.evidenceIds()
            );
        }

        JsonNode generatedAttrs =
                mapper.valueToTree(
                        generated.attrs()
                );

        JsonNode extractionAttrs =
                mapper.valueToTree(
                        extraction.attrs()
                );

        if (!Objects.equals(
                generatedAttrs,
                extractionAttrs
        )) {
            reasons.add(
                    "attrs expected="
                            + generatedAttrs
                            + ", actual="
                            + extractionAttrs
            );
        }

        return List.copyOf(reasons);
    }

    private static void compare(
            List<String> reasons,
            String field,
            Object expected,
            Object actual
    ) {
        if (!Objects.equals(expected, actual)) {
            reasons.add(
                    field
                            + " expected="
                            + expected
                            + ", actual="
                            + actual
            );
        }
    }

    private static boolean sameDecimal(
            BigDecimal left,
            BigDecimal right
    ) {
        if (left == null || right == null) {
            return left == null && right == null;
        }

        return left.compareTo(right) == 0;
    }

    private static String normalizeCode(
            String value
    ) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim()
                .toLowerCase(Locale.ROOT);
    }
}
