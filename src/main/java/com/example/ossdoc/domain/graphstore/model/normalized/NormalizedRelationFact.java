package com.example.ossdoc.domain.graphstore.model.normalized;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GraphStore 내부에서 사용하는 정규화된 relation 한 건.
 *
 * RawRelationFactDto로 읽은 relation 정보를
 * Edge 변환 단계까지 손실 없이 전달한다.
 */
public record NormalizedRelationFact(
        String kind,
        String srcSymbol,
        String dstSymbol,
        String dstRawRef,
        String origin,
        String derivation,
        String resolutionStatus,
        String resolutionReason,
        Integer callSiteLine,
        BigDecimal confidenceHint,
        Map<String, Object> attrs,
        List<String> evidenceIds
) {

    public NormalizedRelationFact {
        if (derivation == null || derivation.isBlank()) {
            derivation = inferDefaultDerivation(origin);
        }

        attrs = attrs == null || attrs.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attrs));

        evidenceIds = evidenceIds == null
                ? List.of()
                : List.copyOf(evidenceIds);
    }

    private static String inferDefaultDerivation(String origin) {
        if (origin != null && "derived".equalsIgnoreCase(origin.trim())) {
            return "derived";
        }

        return "direct";
    }
}