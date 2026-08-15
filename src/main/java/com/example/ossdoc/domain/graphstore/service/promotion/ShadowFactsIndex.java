package com.example.ossdoc.domain.graphstore.service.promotion;

import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedFactsDocument;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedObservationFact;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedSymbolFact;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Collections;

/**
 * semantic promotion shadow 단계에서 반복해서 쓰는 facts 조회 인덱스.
 *
 * 여러 generator가 같은 symbols/observations를 매번 순회해 map/list를 다시 만들지 않도록
 * ingest 시작 시 한 번 구성하고 shadow 분석 전체에서 재사용한다.
 */
public record ShadowFactsIndex(
        List<NormalizedObservationFact> observations,
        List<NormalizedSymbolFact> symbols,
        Map<String, NormalizedSymbolFact> symbolsById,
        Map<String, List<NormalizedSymbolFact>> symbolsByKind
) {

    public static ShadowFactsIndex from(NormalizedFactsDocument facts) {
        if (facts == null) {
            return new ShadowFactsIndex(List.of(), List.of(), Map.of(), Map.of());
        }

        List<NormalizedObservationFact> observations = facts.observations() == null
                ? List.of()
                : List.copyOf(facts.observations());
        List<NormalizedSymbolFact> symbols = facts.symbols() == null
                ? List.of()
                : List.copyOf(facts.symbols());

        Map<String, NormalizedSymbolFact> symbolsById = new LinkedHashMap<>();
        Map<String, List<NormalizedSymbolFact>> symbolsByKind = symbols.stream()
                .filter(symbol -> symbol != null && normalizeCode(symbol.kind()) != null)
                .collect(
                        LinkedHashMap::new,
                        (result, symbol) -> result
                                .computeIfAbsent(normalizeCode(symbol.kind()), ignored -> new java.util.ArrayList<>())
                                .add(symbol),
                        Map::putAll
                );

        for (NormalizedSymbolFact symbol : symbols) {
            if (symbol == null || trimToNull(symbol.symbol()) == null) {
                continue;
            }
            symbolsById.put(symbol.symbol(), symbol);
        }

        symbolsByKind.replaceAll((kind, values) -> List.copyOf(values));

        return new ShadowFactsIndex(
                observations,
                symbols,
                Collections.unmodifiableMap(symbolsById),
                Collections.unmodifiableMap(symbolsByKind)
        );
    }

    public List<NormalizedSymbolFact> symbolsOfKind(String kind) {
        String normalized = normalizeCode(kind);
        if (normalized == null) {
            return List.of();
        }
        return symbolsByKind.getOrDefault(normalized, List.of());
    }

    private static String normalizeCode(String value) {
        String trimmed = trimToNull(value);
        return trimmed == null ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
