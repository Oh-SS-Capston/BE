package com.example.ossdoc.domain.graphstore.converter;

import com.example.ossdoc.domain.graphstore.entity.Evidence;
import com.example.ossdoc.domain.graphstore.enums.EvidenceType;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedEvidenceFact;
import com.example.ossdoc.domain.module.entity.FileIndex;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class FactsEvidenceConverter {

    private final ObjectMapper objectMapper;

    public FactsEvidenceConverter(
            ObjectMapper objectMapper
    ) {
        this.objectMapper = objectMapper;
    }

    /**
     * 정규화된 Evidence를 GraphStore Entity로 손실 없이 변환한다.
     */
    public Evidence toEntity(
            RepoRun run,
            NormalizedEvidenceFact dto,
            FileIndex fileIndex
    ) {
        return new Evidence(
                null,
                run,
                toEvidenceType(dto.type()),
                fileIndex,
                dto.startLine(),
                dto.startCol(),
                dto.endLine(),
                dto.endCol(),
                dto.symbol(),
                dto.snippet(),
                dto.hash(),
                dto.id(),
                toJson(dto.attrs())
        );
    }

    private JsonNode toJson(
            Map<String, Object> attrs
    ) {
        if (attrs == null || attrs.isEmpty()) {
            return null;
        }

        return objectMapper.valueToTree(attrs);
    }

    /**
     * facts 원본 type 문자열을 내부 EvidenceType으로 변환한다.
     */
    private EvidenceType toEvidenceType(String value) {
        if (value == null) {
            return EvidenceType.AST;
        }

        return switch (value.trim().toUpperCase()) {
            case "BYTECODE" -> EvidenceType.BYTECODE;
            case "RESOURCE" -> EvidenceType.RESOURCE;
            case "README" -> EvidenceType.README;
            case "TEST" -> EvidenceType.TEST;
            case "SQL" -> EvidenceType.SQL;
            default -> EvidenceType.AST;
        };
    }
}
