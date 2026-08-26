package com.example.ossdoc.domain.extraction.service.support.evidence;

import com.example.ossdoc.domain.extraction.dto.model.EvidenceFact;
import com.example.ossdoc.domain.extraction.enums.EvidenceType;
import com.example.ossdoc.domain.extraction.service.support.util.EvidenceIdGenerator;
import com.github.javaparser.Range;
import com.github.javaparser.ast.Node;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** AST 노드의 정확한 Range를 EvidenceFact로 변환한다. */
public final class AstEvidenceFactory {

    private AstEvidenceFactory() {
    }

    public static EvidenceFact create(
            String relativePath,
            List<String> sourceLines,
            Node node,
            String ownerSymbol,
            EvidenceType evidenceType,
            EvidenceGranularity granularity,
            String role
    ) {
        EvidenceType effectiveType = evidenceType == null
                ? EvidenceType.AST
                : evidenceType;

        EvidenceGranularity effectiveGranularity =
                granularity == null
                        ? EvidenceGranularity.EXPRESSION
                        : granularity;

        String normalizedRole = normalizeRole(role);

        Integer startLine = null;
        Integer startCol = null;
        Integer endLine = null;
        Integer endCol = null;

        if (node != null && node.getRange().isPresent()) {
            Range range = node.getRange().orElseThrow();
            startLine = range.begin.line;
            startCol = range.begin.column;
            endLine = range.end.line;
            endCol = range.end.column;
        }

        String snippet = SourceRangeSnippetExtractor.extract(
                sourceLines,
                node
        );

        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("granularity", effectiveGranularity.code());

        if (normalizedRole != null) {
            attrs.put("role", normalizedRole);
        }

        /*
         * 같은 AST 노드가 ANNOTATED_WITH, endpoint, bean 등 여러 사실의
         * 근거가 될 수 있으므로 role/granularity를 ID identity에 포함한다.
         * EvidenceFact.symbol은 실제 소유 Symbol을 그대로 유지한다.
         */
        String identitySymbol = String.join("|",
                ownerSymbol == null ? "" : ownerSymbol,
                effectiveGranularity.code(),
                normalizedRole == null ? "" : normalizedRole
        );

        String evidenceId = EvidenceIdGenerator.generate(
                effectiveType,
                relativePath,
                startLine,
                startCol,
                endLine,
                endCol,
                identitySymbol
        );

        return EvidenceFact.builder()
                .id(evidenceId)
                .type(effectiveType)
                .path(relativePath)
                .startLine(startLine)
                .endLine(endLine)
                .startCol(startCol)
                .endCol(endCol)
                .symbol(ownerSymbol)
                .snippet(snippet)
                .hash(snippet == null || snippet.isBlank()
                        ? null
                        : Integer.toHexString(snippet.hashCode()))
                .attrs(Map.copyOf(attrs))
                .build();
    }

    private static String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return null;
        }
        return role.trim();
    }
}
