package com.example.ossdoc.domain.extraction.service.support.evidence;

import com.example.ossdoc.domain.extraction.dto.model.EvidenceFact;
import com.example.ossdoc.domain.extraction.enums.EvidenceType;
import com.example.ossdoc.domain.extraction.service.support.util.EvidenceIdGenerator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * class 파일의 타입·필드·메서드 어노테이션을 독립 Evidence로 변환한다.
 *
 * class 파일은 일반적으로 어노테이션의 소스 line/column을 제공하지 않으므로
 * owner symbol, annotation index, descriptor, role을 안정적인 identity로 사용한다.
 */
public final class BytecodeAnnotationEvidenceFactory {

    private BytecodeAnnotationEvidenceFactory() {
    }

    public static EvidenceFact create(
            String relativePath,
            String module,
            String ownerSymbol,
            String descriptor,
            String qualifiedName,
            boolean runtimeVisible,
            int annotationIndex,
            String role,
            Map<String, Object> values
    ) {
        String effectiveName =
                normalizeAnnotationName(
                        qualifiedName,
                        descriptor
                );

        String effectiveRole =
                role == null || role.isBlank()
                        ? "annotation"
                        : role.trim();

        Map<String, Object> normalizedValues =
                normalizeValues(values);

        String snippet = buildSnippet(
                effectiveName,
                normalizedValues
        );

        Map<String, Object> attrs =
                new LinkedHashMap<>();

        attrs.put(
                "granularity",
                EvidenceGranularity.ANNOTATION.code()
        );
        attrs.put("role", effectiveRole);
        attrs.put("annotation_name", effectiveName);
        attrs.put("descriptor", descriptor);
        attrs.put("runtime_visible", runtimeVisible);
        attrs.put("annotation_index", annotationIndex);
        attrs.put("class_file", true);

        if (!normalizedValues.isEmpty()) {
            attrs.put("values", normalizedValues);
        }

        if (module != null && !module.isBlank()) {
            attrs.put("module", module);
        }

        String identity = String.join(
                "|",
                safe(ownerSymbol),
                effectiveRole,
                String.valueOf(annotationIndex),
                safe(descriptor),
                effectiveName
        );

        String evidenceId =
                EvidenceIdGenerator.generate(
                        EvidenceType.BYTECODE,
                        relativePath,
                        null,
                        null,
                        null,
                        null,
                        identity
                );

        return EvidenceFact.builder()
                .id(evidenceId)
                .type(EvidenceType.BYTECODE)
                .path(relativePath)
                .symbol(ownerSymbol)
                .snippet(snippet)
                .hash(
                        snippet == null || snippet.isBlank()
                                ? null
                                : Integer.toHexString(
                                        snippet.hashCode()
                                )
                )
                .attrs(Map.copyOf(attrs))
                .build();
    }

    private static String buildSnippet(
            String qualifiedName,
            Map<String, Object> values
    ) {
        StringBuilder snippet =
                new StringBuilder("@")
                        .append(qualifiedName);

        if (values == null || values.isEmpty()) {
            return snippet.toString();
        }

        snippet.append('(');

        boolean first = true;

        for (Map.Entry<String, Object> entry
                : values.entrySet()) {
            if (!first) {
                snippet.append(", ");
            }

            snippet.append(entry.getKey())
                    .append('=')
                    .append(formatValue(entry.getValue()));

            first = false;
        }

        snippet.append(')');
        return snippet.toString();
    }

    /**
     * PostgreSQL의 text/jsonb는 U+0000(NUL)을 저장할 수 없다.
     *
     * JSON 스펙상 \\u0000은 유효하지만 Postgres는 다음과 같이 INSERT를 거부한다.
     *   SQLState 22P05 — unsupported Unicode escape sequence
     *   \\u0000 cannot be converted to text
     *
     * bytecode 어노테이션 값에는 NUL이 실제로 들어온다.
     * 대표 사례가 Kotlin 컴파일러가 모든 클래스에 붙이는 @kotlin.Metadata로,
     * d1/d2 필드에 메타데이터를 인코딩한 문자열이 담기며 그 안에 NUL이 섞인다.
     * (JUnit 분석 시 facts.json에 NUL 156개가 유입되어 artifact 저장이 실패했다.)
     *
     * facts.json은 S3와 DB(artifact.meta JSONB) 양쪽에 저장되는데,
     * S3는 통과하고 DB만 죽으므로 파이프라인이 마지막 단계에서 실패한다.
     * 따라서 evidence를 만드는 시점에 제거해 두는 편이 안전하다.
     *
     * NUL만 제거한다. 다른 제어문자는 Postgres가 저장할 수 있으므로
     * 원본 보존을 위해 그대로 둔다.
     */
    private static String stripNulCharacters(String text) {
        if (text == null || text.indexOf('\0') < 0) {
            return text;
        }
        return text.replace("\0", "");
    }

    private static String formatValue(Object value) {
        if (value == null) {
            return "null";
        }

        if (value instanceof String text) {
            return "\"" + stripNulCharacters(text) + "\"";
        }

        if (value instanceof List<?> list) {
            return list.stream()
                    .map(
                            BytecodeAnnotationEvidenceFactory
                                    ::formatValue
                    )
                    .toList()
                    .toString();
        }

        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted =
                    new TreeMap<>();

            for (Map.Entry<?, ?> entry
                    : map.entrySet()) {
                sorted.put(
                        String.valueOf(entry.getKey()),
                        entry.getValue()
                );
            }

            return sorted.entrySet()
                    .stream()
                    .map(entry ->
                            entry.getKey()
                                    + "="
                                    + formatValue(
                                    entry.getValue()
                            )
                    )
                    .toList()
                    .toString();
        }

        // char 등 String이 아닌 값도 NUL을 품을 수 있으므로 마지막 관문에서 한 번 더 막는다.
        return stripNulCharacters(String.valueOf(value));
    }

    private static Map<String, Object> normalizeValues(
            Map<String, Object> values
    ) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }

        TreeMap<String, Object> sorted =
                new TreeMap<>();

        for (Map.Entry<String, Object> entry
                : values.entrySet()) {
            if (entry.getKey() == null
                    || entry.getValue() == null) {
                continue;
            }

            sorted.put(
                    entry.getKey(),
                    normalizeValue(entry.getValue())
            );
        }

        if (sorted.isEmpty()) {
            return Map.of();
        }

        return Collections.unmodifiableMap(
                new LinkedHashMap<>(sorted)
        );
    }

    private static Object normalizeValue(Object value) {
        /*
         * snippet뿐 아니라 attrs.values에도 원본 문자열이 그대로 실린다.
         * 두 곳 모두 facts.json → artifact.meta(JSONB)로 흘러가므로
         * snippet만 정제하면 저장은 여전히 실패한다.
         */
        if (value instanceof String text) {
            return stripNulCharacters(text);
        }

        if (value instanceof Iterable<?> iterable) {
            List<Object> result = new ArrayList<>();

            for (Object item : iterable) {
                if (item != null) {
                    result.add(normalizeValue(item));
                }
            }

            return List.copyOf(result);
        }

        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> result =
                    new TreeMap<>();

            for (Map.Entry<?, ?> entry
                    : map.entrySet()) {
                if (entry.getKey() != null
                        && entry.getValue() != null) {
                    result.put(
                            String.valueOf(entry.getKey()),
                            normalizeValue(
                                    entry.getValue()
                            )
                    );
                }
            }

            return Collections.unmodifiableMap(
                    new LinkedHashMap<>(result)
            );
        }

        return value;
    }

    private static String normalizeAnnotationName(
            String qualifiedName,
            String descriptor
    ) {
        if (qualifiedName != null
                && !qualifiedName.isBlank()) {
            return qualifiedName;
        }

        if (descriptor == null || descriptor.isBlank()) {
            return "unknown.Annotation";
        }

        String normalized = descriptor;

        if (normalized.startsWith("L")
                && normalized.endsWith(";")) {
            normalized = normalized.substring(
                    1,
                    normalized.length() - 1
            );
        }

        return normalized.replace('/', '.');
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
