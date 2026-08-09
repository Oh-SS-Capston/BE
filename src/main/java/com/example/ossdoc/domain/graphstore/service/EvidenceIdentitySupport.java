package com.example.ossdoc.domain.graphstore.service;

import com.example.ossdoc.domain.graphstore.entity.Evidence;
import com.example.ossdoc.domain.graphstore.enums.EvidenceType;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * GraphStore Evidence 중복 판정 정책.
 *
 * rawId가 존재하는 신규 facts.json Evidence는 rawId를 canonical identity로
 * 사용한다. 따라서 snippet/hash가 같아도 role이 다른 Evidence는 분리된다.
 *
 * rawId가 없는 구버전 데이터에만 전체 저장 필드 기반 signature를 사용한다.
 */
final class EvidenceIdentitySupport {

    private EvidenceIdentitySupport() {
    }

    static Evidence findExisting(
            Map<String, Evidence> rawIdLookup,
            Map<Signature, Evidence> signatureLookup,
            Evidence candidate
    ) {
        if (candidate == null) {
            return null;
        }

        String rawId = normalizeBlank(
                candidate.getRawId()
        );

        if (rawId != null) {
            return rawIdLookup.get(rawId);
        }

        Signature signature = signatureOf(candidate);

        return signature == null
                ? null
                : signatureLookup.get(signature);
    }

    static void register(
            Map<String, Evidence> rawIdLookup,
            Map<Signature, Evidence> signatureLookup,
            Evidence evidence
    ) {
        if (evidence == null) {
            return;
        }

        String rawId = normalizeBlank(
                evidence.getRawId()
        );

        if (rawId != null) {
            rawIdLookup.putIfAbsent(
                    rawId,
                    evidence
            );
            return;
        }

        Signature signature = signatureOf(evidence);

        if (signature != null) {
            signatureLookup.putIfAbsent(
                    signature,
                    evidence
            );
        }
    }

    static Signature signatureOf(Evidence evidence) {
        if (evidence == null
                || evidence.getEvidenceType() == null) {
            return null;
        }

        Long fileId = evidence.getFile() == null
                ? null
                : evidence.getFile().getFileId();

        return new Signature(
                evidence.getEvidenceType(),
                fileId,
                evidence.getStartLine(),
                evidence.getStartCol(),
                evidence.getEndLine(),
                evidence.getEndCol(),
                evidence.getSymbol(),
                evidence.getSnippet(),
                evidence.getHash(),
                canonicalAttrs(evidence.getAttrs())
        );
    }

    private static String canonicalAttrs(
            JsonNode attrs
    ) {
        if (attrs == null || attrs.isNull()) {
            return null;
        }

        return attrs.toString();
    }

    private static String normalizeBlank(
            String value
    ) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();

        return trimmed.isEmpty()
                ? null
                : trimmed;
    }

    record Signature(
            EvidenceType evidenceType,
            Long fileId,
            Integer startLine,
            Integer startCol,
            Integer endLine,
            Integer endCol,
            String symbol,
            String snippet,
            String hash,
            String attrsCanonical
    ) {
    }
}
