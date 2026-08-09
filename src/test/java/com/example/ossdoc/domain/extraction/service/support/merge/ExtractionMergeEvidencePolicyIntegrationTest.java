package com.example.ossdoc.domain.extraction.service.support.merge;

import com.example.ossdoc.domain.extraction.dto.model.EvidenceFact;
import com.example.ossdoc.domain.extraction.enums.EvidenceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExtractionMergeEvidencePolicyIntegrationTest {

    @Test
    @DisplayName("root/module merge 내부에서도 동일 Evidence ID의 정확한 정보를 보존한다")
    void extractionMergeUsesCommonEvidencePolicy()
            throws Exception {
        ExtractionMergeSupport support =
                new ExtractionMergeSupport();

        Map<String, EvidenceFact> target =
                new LinkedHashMap<>();

        target.put(
                "evidence-1",
                EvidenceFact.builder()
                        .id("evidence-1")
                        .type(EvidenceType.AST)
                        .path("src/main/java/sample/Sample.java")
                        .snippet(
                                "service.call(); other.call();"
                        )
                        .build()
        );

        Map<String, EvidenceFact> source = Map.of(
                "evidence-1",
                EvidenceFact.builder()
                        .id("evidence-1")
                        .type(EvidenceType.AST)
                        .path(
                                "src/main/java/sample/Sample.java"
                        )
                        .startLine(7)
                        .endLine(7)
                        .startCol(9)
                        .endCol(22)
                        .symbol(
                                "method:sample.Sample#call()"
                        )
                        .snippet("service.call()")
                        .attrs(Map.of(
                                "granularity", "expression",
                                "role", "method_call"
                        ))
                        .build()
        );

        Method mergeEvidence =
                ExtractionMergeSupport.class
                        .getDeclaredMethod(
                                "mergeEvidence",
                                Map.class,
                                Map.class
                        );

        mergeEvidence.setAccessible(true);
        mergeEvidence.invoke(support, target, source);

        EvidenceFact merged = target.get("evidence-1");

        assertEquals("service.call()", merged.snippet());
        assertEquals(7, merged.startLine());
        assertEquals(9, merged.startCol());
        assertEquals(
                "method_call",
                merged.attrs().get("role")
        );
    }
}
