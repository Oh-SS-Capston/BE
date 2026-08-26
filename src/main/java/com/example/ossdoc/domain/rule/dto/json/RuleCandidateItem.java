package com.example.ossdoc.domain.rule.dto.json;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;

import java.math.BigDecimal;
import java.util.List;

@Builder
public record RuleCandidateItem(
        Long candidateId,
        String ruleKey,
        String candidateKind,
        String confidence,
        String status,
        String source,

        String subjectSymbolId,
        String subjectQualifiedName,

        String groupId,
        String title,
        String description,
        String fingerprint,

        BigDecimal score,
        Integer supportCount,
        Boolean publicApiRelated,

        JsonNode summary,
        JsonNode impact,
        JsonNode meta,

        Boolean estimated,
        String qualityLabel,
        String qualityReason,
        Integer evidenceCount,

        List<RuleCandidateEvidenceJson> evidences
) {
}
