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

    private static String formatValue(Object value) {
        if (value == null) {
            return "null";
        }

        if (value instanceof String text) {
            return "\"" + text + "\"";
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

        return String.valueOf(value);
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
