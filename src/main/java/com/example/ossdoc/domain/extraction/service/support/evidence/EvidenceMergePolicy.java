package com.example.ossdoc.domain.extraction.service.support.evidence;

import com.example.ossdoc.domain.extraction.dto.model.EvidenceFact;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 여러 extraction chunk 또는 AST/ASM composer 단계에서
 * 동일 Evidence ID가 다시 유입될 때 적용하는 공통 병합 정책.
 *
 * 핵심 원칙:
 * - role/granularity와 정확한 span을 가진 Evidence를 우선한다.
 * - 표현식 단위의 짧고 정확한 snippet을 행 전체 snippet으로 되돌리지 않는다.
 * - 선택된 snippet과 hash가 서로 불일치하지 않도록 hash를 다시 계산한다.
 * - 한쪽에만 존재하는 path/span/symbol/attrs 정보는 보존한다.
 */
public final class EvidenceMergePolicy {

    private EvidenceMergePolicy() {
    }

    public static EvidenceFact merge(
            EvidenceFact left,
            EvidenceFact right
    ) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }

        EvidenceFact preferred =
                qualityScore(left) >= qualityScore(right)
                        ? left
                        : right;

        EvidenceFact fallback =
                preferred == left ? right : left;

        String snippet = firstNonBlank(
                preferred.snippet(),
                fallback.snippet()
        );

        Map<String, Object> attrs = mergeAttrs(
                fallback.attrs(),
                preferred.attrs()
        );

        return EvidenceFact.builder()
                .id(firstNonBlank(
                        preferred.id(),
                        fallback.id()
                ))
                .type(firstNonNull(
                        preferred.type(),
                        fallback.type()
                ))
                .path(firstNonBlank(
                        preferred.path(),
                        fallback.path()
                ))
                .startLine(firstNonNull(
                        preferred.startLine(),
                        fallback.startLine()
                ))
                .endLine(firstNonNull(
                        preferred.endLine(),
                        fallback.endLine()
                ))
                .startCol(firstNonNull(
                        preferred.startCol(),
                        fallback.startCol()
                ))
                .endCol(firstNonNull(
                        preferred.endCol(),
                        fallback.endCol()
                ))
                .symbol(firstNonBlank(
                        preferred.symbol(),
                        fallback.symbol()
                ))
                .snippet(snippet)
                .hash(resolveHash(
                        snippet,
                        preferred.hash(),
                        fallback.hash()
                ))
                .attrs(attrs)
                .build();
    }

    /**
     * source의 Evidence를 target에 병합한다.
     *
     * map key가 비어 있으면 EvidenceFact.id를 사용하며,
     * 동일 ID가 이미 있으면 {@link #merge(EvidenceFact, EvidenceFact)}를 적용한다.
     */
    public static void mergeInto(
            Map<String, EvidenceFact> target,
            Map<String, EvidenceFact> source
    ) {
        if (target == null
                || source == null
                || source.isEmpty()) {
            return;
        }

        for (Map.Entry<String, EvidenceFact> entry
                : source.entrySet()) {
            EvidenceFact incoming = entry.getValue();

            if (incoming == null) {
                continue;
            }

            String key = firstNonBlank(
                    entry.getKey(),
                    incoming.id()
            );

            if (key == null || key.isBlank()) {
                continue;
            }

            target.merge(
                    key,
                    incoming,
                    EvidenceMergePolicy::merge
            );
        }
    }

    private static int qualityScore(EvidenceFact evidence) {
        if (evidence == null) {
            return Integer.MIN_VALUE;
        }

        int score = 0;
        Map<String, Object> attrs = evidence.attrs();

        if (hasTextAttribute(attrs, "role")) {
            score += 8;
        }

        if (hasTextAttribute(attrs, "granularity")) {
            score += 4;
        }

        if (hasCompleteSpan(evidence)) {
            score += 4;
        } else if (evidence.startLine() != null) {
            score += 1;
        }

        if (evidence.snippet() != null
                && !evidence.snippet().isBlank()) {
            score += 2;
        }

        if (evidence.hash() != null
                && !evidence.hash().isBlank()) {
            score += 1;
        }

        return score;
    }

    private static boolean hasCompleteSpan(
            EvidenceFact evidence
    ) {
        return evidence.startLine() != null
                && evidence.endLine() != null
                && evidence.startCol() != null
                && evidence.endCol() != null;
    }

    private static boolean hasTextAttribute(
            Map<String, Object> attrs,
            String key
    ) {
        if (attrs == null || attrs.isEmpty()) {
            return false;
        }

        Object value = attrs.get(key);

        return value != null
                && !String.valueOf(value).isBlank();
    }

    /**
     * preferred attrs가 충돌 시 우선되도록 fallback을 먼저 넣는다.
     */
    private static Map<String, Object> mergeAttrs(
            Map<String, Object> fallback,
            Map<String, Object> preferred
    ) {
        LinkedHashMap<String, Object> merged =
                new LinkedHashMap<>();

        if (fallback != null) {
            merged.putAll(fallback);
        }

        if (preferred != null) {
            merged.putAll(preferred);
        }

        if (merged.isEmpty()) {
            return Map.of();
        }

        return Collections.unmodifiableMap(
                new LinkedHashMap<>(merged)
        );
    }

    private static String resolveHash(
            String snippet,
            String preferredHash,
            String fallbackHash
    ) {
        if (snippet != null && !snippet.isBlank()) {
            return Integer.toHexString(snippet.hashCode());
        }

        return firstNonBlank(
                preferredHash,
                fallbackHash
        );
    }

    private static <T> T firstNonNull(
            T preferred,
            T fallback
    ) {
        return preferred != null
                ? preferred
                : fallback;
    }

    private static String firstNonBlank(
            String preferred,
            String fallback
    ) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }

        return fallback != null && !fallback.isBlank()
                ? fallback
                : null;
    }
}
