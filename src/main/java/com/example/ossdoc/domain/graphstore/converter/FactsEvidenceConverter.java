package com.example.ossdoc.domain.graphstore.converter;

import com.example.ossdoc.domain.graphstore.dto.facts.EvidenceFactDto;
import com.example.ossdoc.domain.graphstore.entity.Evidence;
import com.example.ossdoc.domain.graphstore.enums.EvidenceType;
import com.example.ossdoc.domain.run.entity.RepoRun;
import org.springframework.stereotype.Component;

@Component
public class FactsEvidenceConverter {

    public Evidence toEntity(RepoRun run, EvidenceFactDto dto) {
        return new Evidence(
                null,
                run,
                toEvidenceType(dto.getType()),
                null, // FileIndex 연동 전이라 null
                dto.getStartLine(),
                dto.getEndLine(),
                dto.getSnippet(),
                dto.getHash()
        );
    }

    private EvidenceType toEvidenceType(String value) {
        if (value == null) return EvidenceType.AST;
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