package com.example.ossdoc.domain.extraction.service.composer;

import com.example.ossdoc.domain.extraction.dto.model.EvidenceFact;
import com.example.ossdoc.domain.extraction.dto.model.RelationFact;
import com.example.ossdoc.domain.extraction.enums.EvidenceType;
import com.example.ossdoc.domain.extraction.enums.FactOriginKind;
import com.example.ossdoc.domain.extraction.enums.RelationKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FactsDedupAstAsmEvidenceMergeTest {

    @Test
    @DisplayName("Composer Evidence 병합도 공통 정책을 사용한다")
    void composerEvidenceMergeUsesCommonPolicy() {
        EvidenceFact legacy = EvidenceFact.builder()
                .id("ast-evidence")
                .type(EvidenceType.AST)
                .path("src/main/java/sample/Sample.java")
                .snippet("service.call(); other.call();")
                .build();

        EvidenceFact precise = EvidenceFact.builder()
                .id("ast-evidence")
                .type(EvidenceType.AST)
                .path("src/main/java/sample/Sample.java")
                .startLine(11)
                .endLine(11)
                .startCol(9)
                .endCol(22)
                .snippet("service.call()")
                .attrs(Map.of(
                        "granularity", "expression",
                        "role", "method_call"
                ))
                .build();

        EvidenceFact merged =
                FactsDedupSupport.mergeEvidence(
                        legacy,
                        precise
                );

        assertEquals("service.call()", merged.snippet());
        assertEquals(
                "method_call",
                merged.attrs().get("role")
        );
    }

    @Test
    @DisplayName("동일 관계의 AST·BYTECODE Evidence ID를 모두 유지하고 출처를 승격한다")
    void relationMergeKeepsAstAndBytecodeEvidence() {
        RelationFact ast = RelationFact.builder()
                .kind(RelationKind.CALLS)
                .srcSymbol("method:sample.A#run()")
                .dstSymbol("method:sample.B#call()")
                .evidenceIds(List.of("ast-evidence"))
                .origin(FactOriginKind.AST)
                .confidenceHint(0.9)
                .attrs(Map.of(
                        "expression", "b.call()"
                ))
                .build();

        RelationFact bytecode = RelationFact.builder()
                .kind(RelationKind.CALLS)
                .srcSymbol("method:sample.A#run()")
                .dstSymbol("method:sample.B#call()")
                .evidenceIds(List.of("bytecode-evidence"))
                .origin(FactOriginKind.BYTECODE)
                .confidenceHint(0.95)
                .attrs(Map.of(
                        "opcode_confirmed", true
                ))
                .build();

        RelationFact merged =
                FactsDedupSupport.mergeRelation(
                        ast,
                        bytecode
                );

        assertEquals(
                FactOriginKind.AST_AND_BYTECODE,
                merged.origin()
        );
        assertEquals(2, merged.evidenceIds().size());
        assertTrue(
                merged.evidenceIds().contains(
                        "ast-evidence"
                )
        );
        assertTrue(
                merged.evidenceIds().contains(
                        "bytecode-evidence"
                )
        );
        assertEquals(0.95, merged.confidenceHint());
        assertEquals(
                "b.call()",
                merged.attrs().get("expression")
        );
        assertEquals(
                true,
                merged.attrs().get("opcode_confirmed")
        );
    }
}
