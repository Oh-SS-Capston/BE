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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * DI GraphStore shadow 후보와 Extraction INJECTS Relation을
 * relation identity 및 metadata 단위로 exact 비교한다.
 */
public final class DiShadowParityAnalyzer {

    private static final String TARGET_KIND = "injects";

    private DiShadowParityAnalyzer() {
    }

    public static ObservationPromotionCandidateParityReport compare(
            NormalizedFactsDocument facts,
            ObservationPromotionCandidateGenerationResult generated,
            ObjectMapper objectMapper
    ) {
        return compare(ShadowFactsIndex.from(facts), generated, objectMapper);
    }

    public static ObservationPromotionCandidateParityReport compare(
            ShadowFactsIndex factsIndex,
            ObservationPromotionCandidateGenerationResult generated,
            ObjectMapper objectMapper
    ) {
        ObjectMapper mapper = objectMapper == null
                ? new ObjectMapper().findAndRegisterModules()
                : objectMapper;

        ShadowFactsIndex safeIndex = factsIndex == null
                ? ShadowFactsIndex.from(null)
                : factsIndex;
        // 공통 relation 인덱스를 재사용해 parity 단계의 전체 relations 반복 순회를 줄인다.
        // 성능 최적화: relationByKeyForKinds가 이미 remove 가능한 새 Map을 반환하므로 추가 복사를 생략한다.
        // DI parity의 누락/불일치 판정은 유지하면서 중간 Map 복사 비용만 제거한다.
        Map<String, NormalizedRelationFact> extractionByKey =
                safeIndex.relationByKeyForKinds(Set.of(TARGET_KIND));
        int extractionCount = extractionByKey.size();

        List<ObservationPromotionShadowCandidate> candidates =
                generated == null
                        ? List.of()
                        : generated.candidates();

        List<ObservationPromotionCandidateParityIssue> issues =
                new ArrayList<>();

        for (ObservationPromotionShadowCandidate candidate : candidates) {
            String key = candidate.relationKey();
            NormalizedRelationFact extraction =
                    extractionByKey.remove(key);

            if (extraction == null) {
                issues.add(new ObservationPromotionCandidateParityIssue(
                        key,
                        ObservationPromotionCandidateParityStatus
                                .MISSING_EXTRACTION_RELATION,
                        candidate.observationIndex(),
                        candidate.observationKind(),
                        List.of(
                                "No Extraction INJECTS relation exists "
                                        + "for the generated relation key"
                        )
                ));
                continue;
            }

            List<String> reasons = metadataDifferences(
                    candidate.relation(),
                    extraction,
                    mapper
            );

            issues.add(new ObservationPromotionCandidateParityIssue(
                    key,
                    reasons.isEmpty()
                            ? ObservationPromotionCandidateParityStatus.MATCHED
                            : ObservationPromotionCandidateParityStatus
                            .METADATA_MISMATCH,
                    candidate.observationIndex(),
                    candidate.observationKind(),
                    reasons
            ));
        }

        for (Map.Entry<String, NormalizedRelationFact> entry
                : extractionByKey.entrySet()) {
            issues.add(new ObservationPromotionCandidateParityIssue(
                    entry.getKey(),
                    ObservationPromotionCandidateParityStatus.EXTRACTION_ONLY,
                    null,
                    null,
                    List.of(
                            "Extraction produced an INJECTS relation "
                                    + "that GraphStore shadow generation "
                                    + "did not reproduce"
                    )
            ));
        }

        return new ObservationPromotionCandidateParityReport(
                candidates.size(),
                extractionCount,
                issues
        );
    }

    private static List<String> metadataDifferences(
            NormalizedRelationFact generated,
            NormalizedRelationFact extraction,
            ObjectMapper mapper
    ) {
        List<String> reasons = new ArrayList<>();

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
                normalizeCode(generated.resolutionStatus()),
                normalizeCode(extraction.resolutionStatus())
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
                mapper.valueToTree(generated.attrs());
        JsonNode extractionAttrs =
                mapper.valueToTree(extraction.attrs());

        if (!Objects.equals(generatedAttrs, extractionAttrs)) {
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
                    field + " expected=" + expected + ", actual=" + actual
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

    private static String normalizeCode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
