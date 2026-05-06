package com.example.ossdoc.domain.extraction.service.support.util;

import com.example.ossdoc.domain.extraction.enums.EvidenceType;

import java.util.Objects;

/**
 * evidence id를 결정적으로 생성
 */
public final class EvidenceIdGenerator {

    private EvidenceIdGenerator() {
    }

    public static String generate(EvidenceType kind, String path, Integer startLine, Integer startCol,
                                  Integer endLine, Integer endCol, String symbol) {
        Objects.requireNonNull(kind, "kind must not be null");

        String normalizedPath = RepoPathUtils.normalizeSeparators(path);
        String key = String.join("|",
                "kind=" + kind.code(),
                "path=" + nullToEmpty(normalizedPath),
                "startLine=" + valueOf(startLine),
                "startCol=" + valueOf(startCol),
                "endLine=" + valueOf(endLine),
                "endCol=" + valueOf(endCol),
                "symbol=" + nullToEmpty(symbol)
        );

        return "ev_" + HashSupport.sha256Hex(key).substring(0, 16);
    }

    private static String valueOf(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
