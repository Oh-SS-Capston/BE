package com.example.ossdoc.domain.extraction.service.support.evidence;

import com.github.javaparser.Range;
import com.github.javaparser.ast.Node;

import java.util.List;

/**
 * JavaParser의 1-based line/column Range를 이용해 정확한 소스 조각을 추출한다.
 *
 * 기존의 행 단위 snippet 추출과 달리 시작/종료 column을 반영하므로,
 * 같은 행에 여러 표현식이 있어도 Evidence가 해당 노드만 가리킨다.
 */
public final class SourceRangeSnippetExtractor {

    public static final int DEFAULT_MAX_LENGTH = 300;

    private SourceRangeSnippetExtractor() {
    }

    public static String extract(
            List<String> sourceLines,
            Node node
    ) {
        return extract(sourceLines, node, DEFAULT_MAX_LENGTH);
    }

    public static String extract(
            List<String> sourceLines,
            Node node,
            int maxLength
    ) {
        if (node == null) {
            return null;
        }

        String exact = node.getRange()
                .map(range -> extract(sourceLines, range))
                .orElse(null);

        if (exact == null || exact.isBlank()) {
            exact = node.toString();
        }

        return truncate(exact, maxLength);
    }

    public static String extract(
            List<String> sourceLines,
            Range range
    ) {
        if (sourceLines == null
                || sourceLines.isEmpty()
                || range == null) {
            return null;
        }

        int startLineIndex = range.begin.line - 1;
        int endLineIndex = range.end.line - 1;

        if (startLineIndex < 0
                || endLineIndex < startLineIndex
                || startLineIndex >= sourceLines.size()
                || endLineIndex >= sourceLines.size()) {
            return null;
        }

        String startLine = safeLine(sourceLines.get(startLineIndex));
        String endLine = safeLine(sourceLines.get(endLineIndex));

        int startColumnIndex = clamp(
                range.begin.column - 1,
                0,
                startLine.length()
        );
        int endColumnExclusive = clamp(
                range.end.column,
                0,
                endLine.length()
        );

        if (startLineIndex == endLineIndex) {
            if (startColumnIndex > endColumnExclusive) {
                return null;
            }
            return startLine.substring(
                    startColumnIndex,
                    endColumnExclusive
            );
        }

        StringBuilder result = new StringBuilder();
        result.append(startLine.substring(startColumnIndex));

        for (int lineIndex = startLineIndex + 1;
             lineIndex < endLineIndex;
             lineIndex++) {
            result.append('\n')
                    .append(safeLine(sourceLines.get(lineIndex)));
        }

        result.append('\n')
                .append(endLine, 0, endColumnExclusive);

        return result.toString();
    }

    private static String truncate(
            String value,
            int maxLength
    ) {
        if (value == null) {
            return null;
        }

        int effectiveMax = maxLength <= 0
                ? DEFAULT_MAX_LENGTH
                : maxLength;

        if (value.length() <= effectiveMax) {
            return value;
        }

        return value.substring(0, effectiveMax);
    }

    private static String safeLine(String value) {
        return value == null ? "" : value;
    }

    private static int clamp(
            int value,
            int minimum,
            int maximum
    ) {
        return Math.max(minimum, Math.min(value, maximum));
    }
}
