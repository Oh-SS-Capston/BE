package com.example.ossdoc.domain.extraction.service.support.util;

import com.example.ossdoc.domain.extraction.dto.model.TypeRef;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * TypeRef 생성 보조 유틸
 */
public final class TypeRefFactory {

    private static final Set<String> PRIMITIVES = Set.of(
            "byte", "short", "int", "long", "float", "double", "boolean", "char", "void"
    );

    private TypeRefFactory() {
    }

    public static TypeRef simple(String raw) {
        String normalized = requireRaw(raw);
        return TypeRef.builder()
                .raw(normalized)
                .args(List.of())
                .arrayDim(0)
                .primitive(isPrimitiveName(normalized))
                .unresolved(false)
                .build();
    }

    public static TypeRef unresolved(String raw, String sourceText) {
        String normalized = requireRaw(raw);
        return TypeRef.builder()
                .raw(normalized)
                .args(List.of())
                .arrayDim(0)
                .primitive(isPrimitiveName(normalized))
                .unresolved(true)
                .sourceText(sourceText)
                .build();
    }

    /**
     * 어노테이션 TypeRef. element 값(attributes)을 함께 담는다.
     * 어노테이션은 배열·primitive·제네릭 인자가 없으므로 해당 필드는 기본값으로 둔다.
     */
    public static TypeRef annotation(String raw, Map<String, String> attributes) {
        String normalized = requireRaw(raw);
        return TypeRef.builder()
                .raw(normalized)
                .args(List.of())
                .arrayDim(0)
                .primitive(false)
                .unresolved(false)
                .attributes(normalizeAttributes(attributes))
                .build();
    }

    /** 미해결 어노테이션 TypeRef. 이름만 알 수 있을 때 사용하되 attributes는 보존한다. */
    public static TypeRef annotationUnresolved(String raw, String sourceText, Map<String, String> attributes) {
        String normalized = requireRaw(raw);
        return TypeRef.builder()
                .raw(normalized)
                .args(List.of())
                .arrayDim(0)
                .primitive(false)
                .unresolved(true)
                .sourceText(sourceText)
                .attributes(normalizeAttributes(attributes))
                .build();
    }

    private static Map<String, String> normalizeAttributes(Map<String, String> attributes) {
        // 빈 맵은 null로 환원 → @JsonInclude(NON_NULL)로 marker 어노테이션엔 attributes 키가 붙지 않는다.
        return (attributes == null || attributes.isEmpty()) ? null : Map.copyOf(attributes);
    }

    public static TypeRef parameterized(String raw, List<TypeRef> args, String sourceText) {
        String normalized = requireRaw(raw);
        return TypeRef.builder()
                .raw(normalized)
                .args(args == null ? List.of() : List.copyOf(args))
                .arrayDim(0)
                .primitive(isPrimitiveName(normalized))
                .unresolved(false)
                .sourceText(sourceText)
                .build();
    }

    public static TypeRef arrayOf(TypeRef baseType, int additionalDimensions) {
        Objects.requireNonNull(baseType, "baseType must not be null");
        if (additionalDimensions < 1) {
            throw new IllegalArgumentException("additionalDimensions must be >= 1");
        }

        int current = baseType.arrayDim() == null ? 0 : Math.max(0, baseType.arrayDim());

        return TypeRef.builder()
                .raw(baseType.raw())
                .args(baseType.args() == null ? List.of() : List.copyOf(baseType.args()))
                .arrayDim(current + additionalDimensions)
                .primitive(Boolean.TRUE.equals(baseType.primitive()))
                .unresolved(Boolean.TRUE.equals(baseType.unresolved()))
                .sourceText(baseType.sourceText())
                .build();
    }

    public static boolean isPrimitiveName(String raw) {
        return raw != null && PRIMITIVES.contains(raw);
    }

    private static String requireRaw(String raw) {
        Objects.requireNonNull(raw, "raw must not be null");
        if (raw.isBlank()) {
            throw new IllegalArgumentException("raw must not be blank");
        }
        return raw;
    }
}