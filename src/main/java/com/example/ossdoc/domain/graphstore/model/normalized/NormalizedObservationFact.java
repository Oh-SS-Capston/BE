package com.example.ossdoc.domain.graphstore.model.normalized;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;

/**
 * GraphStore 내부에서 사용하는 정규화된 Observation.
 *
 * origin은 Observation 기반 shadow relation 후보가 Extraction resolver와
 * 동일한 origin·resolution·confidence 정책을 계산하는 데 필요하다.
 */
public record NormalizedObservationFact(
        String kind,
        String siteSymbol,
        String targetSymbol,
        JsonNode targetTypeRef,
        String note,
        String origin,
        BigDecimal confidenceHint,
        JsonNode attrs,
        List<String> evidenceIds
) {

    public NormalizedObservationFact {
        evidenceIds = evidenceIds == null
                ? List.of()
                : List.copyOf(evidenceIds);
    }

    /**
     * 10-3-3A 이전 테스트·수동 생성 코드와의 source compatibility 유지.
     */
    public NormalizedObservationFact(
            String kind,
            String siteSymbol,
            String targetSymbol,
            JsonNode targetTypeRef,
            String note,
            BigDecimal confidenceHint,
            JsonNode attrs,
            List<String> evidenceIds
    ) {
        this(
                kind,
                siteSymbol,
                targetSymbol,
                targetTypeRef,
                note,
                null,
                confidenceHint,
                attrs,
                evidenceIds
        );
    }
}
