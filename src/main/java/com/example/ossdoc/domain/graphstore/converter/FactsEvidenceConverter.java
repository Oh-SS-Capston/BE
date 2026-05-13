package com.example.ossdoc.domain.graphstore.converter;

import com.example.ossdoc.domain.graphstore.entity.Evidence;
import com.example.ossdoc.domain.graphstore.enums.EvidenceType;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedEvidenceFact;
import com.example.ossdoc.domain.module.entity.FileIndex;
import com.example.ossdoc.domain.run.entity.RepoRun;
import org.springframework.stereotype.Component;

@Component
public class FactsEvidenceConverter {

    /**
     * 정규화된 evidence fact를 Evidence 엔티티로 변환한다.
     */
    public Evidence toEntity(RepoRun run, NormalizedEvidenceFact dto, FileIndex fileIndex) {
        return new Evidence(
                null,
                run,
                toEvidenceType(dto.type()),
                fileIndex,
                dto.startLine(),
                dto.endLine(),
                dto.snippet(),
                dto.hash(),
                dto.id()
        );
    }

    /**
     * facts 원본 type 문자열을 내부 EvidenceType으로 변환한다.
     */
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