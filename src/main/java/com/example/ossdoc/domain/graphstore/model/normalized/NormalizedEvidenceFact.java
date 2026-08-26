package com.example.ossdoc.domain.graphstore.model.normalized;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GraphStore 내부에서 사용하는 정규화된 Evidence.
 *
 * 표현식·instruction·annotation 단위의 role/granularity와
 * 추출기별 메타데이터를 attrs에 손실 없이 유지한다.
 */
public record NormalizedEvidenceFact(
        String id,
        String type,
        String path,
        Integer startLine,
        Integer startCol,
        Integer endLine,
        Integer endCol,
        String symbol,
        String snippet,
        String hash,
        Map<String, Object> attrs
) {

    public NormalizedEvidenceFact {
        attrs = attrs == null || attrs.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(
                        new LinkedHashMap<>(attrs)
                );
    }
}
