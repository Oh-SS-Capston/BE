package com.example.ossdoc.domain.rule.dto.json;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;

import java.math.BigDecimal;

@Builder
public record RuleCandidateEvidenceJson(
        Long candidateEvidenceId,
        Long signalId,
        Long evidenceId,
        String rawId,
        String evidenceType,
        Long edgeId,
        String role,
        BigDecimal weight,
        String filePath,
        Integer startLine,
        Integer endLine,
        String snippet,
        String note,

        String edgeType,
        String edgeOrigin,
        String edgeDerivationKind,
        String edgeResolution,
        String edgeResolutionReason,
        BigDecimal edgeConfidence,
        Integer edgeCallSiteLine,
        Boolean edgeDefaultVisible,
        JsonNode edgeAttrs,

        BigDecimal signalConfidenceHint,
        JsonNode signalMeta
) {
}