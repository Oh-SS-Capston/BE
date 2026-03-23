package com.example.ossdoc.domain.extraction.service.support;

import com.example.ossdoc.domain.extraction.dto.model.SignatureFact;
import com.example.ossdoc.domain.extraction.dto.model.TypeRef;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * facts 단계용 symbol id 생성 규칙
 */
public final class SymbolIdFactory {

    private SymbolIdFactory() {
    }

    public static String module(String moduleName) {
        return "module:" + requireText(moduleName, "moduleName");
    }

    public static String packageSymbol(String packageName) {
        return "package:" + requireText(packageName, "packageName");
    }

    public static String type(String qualifiedName) {
        return "type:" + requireText(qualifiedName, "qualifiedName");
    }

    public static String field(String ownerTypeQualifiedName, String fieldName) {
        return "field:" + requireText(ownerTypeQualifiedName, "ownerTypeQualifiedName")
                + "#" + requireText(fieldName, "fieldName");
    }

    public static String constructor(String ownerTypeQualifiedName, SignatureFact signature) {
        return "ctor:" + requireText(ownerTypeQualifiedName, "ownerTypeQualifiedName")
                + "#<init>(" + renderParams(signature) + ")";
    }

    public static String method(String ownerTypeQualifiedName, String methodName, SignatureFact signature) {
        return "method:" + requireText(ownerTypeQualifiedName, "ownerTypeQualifiedName")
                + "#" + requireText(methodName, "methodName")
                + "(" + renderParams(signature) + ")";
    }

    private static String renderParams(SignatureFact signature) {
        if (signature == null || signature.params() == null || signature.params().isEmpty()) {
            return "";
        }

        return signature.params().stream()
                .map(SymbolIdFactory::renderTypeRef)
                .collect(Collectors.joining(","));
    }

    private static String renderTypeRef(TypeRef typeRef) {
        if (typeRef == null) {
            return "?";
        }

        String base = firstNonBlank(typeRef.raw(), typeRef.sourceText(), "?");
        int arrayDim = typeRef.arrayDim() == null ? 0 : Math.max(0, typeRef.arrayDim());

        if (arrayDim == 0) {
            return base;
        }

        return base + "[]".repeat(arrayDim);
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}