package com.example.ossdoc.domain.graphstore.converter;

import com.example.ossdoc.domain.graphstore.entity.Evidence;
import com.example.ossdoc.domain.graphstore.enums.EvidenceType;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedEvidenceFact;
import com.example.ossdoc.domain.module.entity.FileIndex;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

class FactsEvidenceConverterMetadataTest {

    private final FactsEvidenceConverter converter =
            new FactsEvidenceConverter(
                    new ObjectMapper()
                            .findAndRegisterModules()
            );

    @Test
    @DisplayName("정규화 Evidence 메타데이터를 Entity 필드와 JSON attrs에 보존한다")
    void convertsAllEvidenceMetadata() {
        RepoRun run = mock(RepoRun.class);
        FileIndex file = mock(FileIndex.class);

        NormalizedEvidenceFact fact =
                new NormalizedEvidenceFact(
                        "evidence-role-1",
                        "BYTECODE",
                        "build/classes/sample/Sample.class",
                        27,
                        4,
                        27,
                        18,
                        "method:sample.Sample#run()",
                        "INVOKEVIRTUAL sample.Target.call()V",
                        "hash-1",
                        Map.of(
                                "granularity", "instruction",
                                "role", "method_call",
                                "instruction_index", 6,
                                "opcode_name", "INVOKEVIRTUAL"
                        )
                );

        Evidence entity =
                converter.toEntity(
                        run,
                        fact,
                        file
                );

        assertSame(run, entity.getRun());
        assertSame(file, entity.getFile());
        assertEquals(
                EvidenceType.BYTECODE,
                entity.getEvidenceType()
        );
        assertEquals(27, entity.getStartLine());
        assertEquals(4, entity.getStartCol());
        assertEquals(27, entity.getEndLine());
        assertEquals(18, entity.getEndCol());
        assertEquals(
                "method:sample.Sample#run()",
                entity.getSymbol()
        );
        assertEquals(
                "evidence-role-1",
                entity.getRawId()
        );
        assertEquals(
                "instruction",
                entity.getAttrs()
                        .path("granularity")
                        .asText()
        );
        assertEquals(
                "method_call",
                entity.getAttrs()
                        .path("role")
                        .asText()
        );
        assertEquals(
                6,
                entity.getAttrs()
                        .path("instruction_index")
                        .asInt()
        );
    }
}
