package com.example.ossdoc.domain.graphstore.model.projection;

import com.example.ossdoc.domain.graphstore.enums.EvidenceType;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * GraphStore evidence 중복 판별용 경량 조회 row.
 *
 * <p>Evidence 엔티티 전체를 로딩하면 snippet/attrs/file 연관까지 영속성 컨텍스트에 올라가므로
 * 대형 프로젝트에서 heap 사용량이 급증한다. 중복 판별과 후속 evidenceMap 구성에 필요한 필드만 조회한다.</p>
 */
public record EvidenceLookupRow(
        Long evidenceId,
        EvidenceType evidenceType,
        Long fileId,
        String filePath,
        String fileType,
        Integer startLine,
        Integer startCol,
        Integer endLine,
        Integer endCol,
        String symbol,
        String snippet,
        String hash,
        String rawId,
        JsonNode attrs
) {
}
