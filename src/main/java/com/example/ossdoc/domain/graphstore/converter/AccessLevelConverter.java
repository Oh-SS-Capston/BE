package com.example.ossdoc.domain.graphstore.converter;

import com.example.ossdoc.domain.graphstore.enums.AccessLevel;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = false)
public class AccessLevelConverter implements AttributeConverter<AccessLevel, String> {

    @Override
    public String convertToDatabaseColumn(AccessLevel attribute) {
        if (attribute == null) return null;
        return switch (attribute) {
            case PUBLIC -> "public";
            case PROTECTED -> "protected";
            case PACKAGE -> "package";
            case PRIVATE -> "private";
        };
    }

    @Override
    public AccessLevel convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;
        return switch (dbData) {
            case "public" -> AccessLevel.PUBLIC;
            case "protected" -> AccessLevel.PROTECTED;
            case "package" -> AccessLevel.PACKAGE;
            case "private" -> AccessLevel.PRIVATE;
            default -> throw new IllegalArgumentException("Unknown access_level: " + dbData);
        };
    }
}