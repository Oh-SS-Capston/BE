package com.example.ossdoc.domain.extraction.service.support.evidence;

import com.example.ossdoc.domain.extraction.dto.model.EvidenceFact;
import com.example.ossdoc.domain.extraction.enums.EvidenceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class EvidenceMergePolicyTest {

    @Test
    @DisplayName("role 기반 정확한 표현식 Evidence가 기존 행 전체 snippet보다 우선된다")
    void roleAwareEvidenceWinsOverLegacyLineSnippet() {
        EvidenceFact legacy = EvidenceFact.builder()
                .id("evidence-1")
                .type(EvidenceType.AST)
                .path("src/main/java/sample/Sample.java")
                .startLine(10)
                .endLine(10)
                .startCol(9)
                .endCol(21)
                .symbol("method:sample.Sample#call()")
                .snippet(
                        "service.call(); other.call();"
                )
                .hash("legacy-hash")
                .attrs(Map.of(
                        "legacy", true
                ))
                .build();

        EvidenceFact precise = EvidenceFact.builder()
                .id("evidence-1")
                .type(EvidenceType.AST)
                .path("src/main/java/sample/Sample.java")
                .startLine(10)
                .endLine(10)
                .startCol(9)
                .endCol(21)
                .symbol("method:sample.Sample#call()")
                .snippet("service.call()")
                .hash("precise-hash")
                .attrs(Map.of(
                        "granularity", "expression",
                        "role", "method_call"
                ))
                .build();

        EvidenceFact merged =
                EvidenceMergePolicy.merge(legacy, precise);

        assertEquals("service.call()", merged.snippet());
        assertEquals(
                Integer.toHexString(
                        "service.call()".hashCode()
                ),
                merged.hash()
        );
        assertEquals(
                "expression",
                merged.attrs().get("granularity")
        );
        assertEquals(
                "method_call",
                merged.attrs().get("role")
        );
        assertEquals(true, merged.attrs().get("legacy"));
    }

    @Test
    @DisplayName("선호 Evidence에 없는 경로·범위 정보는 다른 Evidence에서 보완한다")
    void missingFieldsAreEnrichedFromFallback() {
        EvidenceFact preferred = EvidenceFact.builder()
                .id("evidence-2")
                .type(EvidenceType.AST)
                .snippet("@Bean")
                .attrs(Map.of(
                        "granularity", "annotation",
                        "role", "bean_provider"
                ))
                .build();

        EvidenceFact fallback = EvidenceFact.builder()
                .id("evidence-2")
                .type(EvidenceType.AST)
                .path("src/main/java/sample/Config.java")
                .startLine(5)
                .endLine(5)
                .startCol(5)
                .endCol(9)
                .symbol("method:sample.Config#service()")
                .build();

        EvidenceFact merged =
                EvidenceMergePolicy.merge(preferred, fallback);

        assertEquals(
                "src/main/java/sample/Config.java",
                merged.path()
        );
        assertEquals(5, merged.startLine());
        assertEquals(5, merged.endLine());
        assertEquals(5, merged.startCol());
        assertEquals(9, merged.endCol());
        assertEquals(
                "method:sample.Config#service()",
                merged.symbol()
        );
        assertEquals("@Bean", merged.snippet());
    }

    @Test
    @DisplayName("mergeInto는 빈 map key를 Evidence ID로 보정하고 동일 ID를 병합한다")
    void mergeIntoUsesEvidenceIdAndMergesDuplicates() {
        Map<String, EvidenceFact> target =
                new LinkedHashMap<>();

        EvidenceFact sparse = EvidenceFact.builder()
                .id("evidence-3")
                .type(EvidenceType.BYTECODE)
                .path("build/classes/java/main/sample/Sample.class")
                .build();

        EvidenceFact rich = EvidenceFact.builder()
                .id("evidence-3")
                .type(EvidenceType.BYTECODE)
                .path("build/classes/java/main/sample/Sample.class")
                .symbol("type:sample.Sample")
                .attrs(Map.of(
                        "class_file", true,
                        "module", "sample"
                ))
                .build();

        EvidenceMergePolicy.mergeInto(
                target,
                Map.of("", sparse)
        );

        EvidenceMergePolicy.mergeInto(
                target,
                Map.of("evidence-3", rich)
        );

        assertEquals(1, target.size());
        assertEquals(
                "type:sample.Sample",
                target.get("evidence-3").symbol()
        );
        assertEquals(
                true,
                target.get("evidence-3")
                        .attrs()
                        .get("class_file")
        );
    }

    @Test
    @DisplayName("null 반대편은 기존 인스턴스를 그대로 유지한다")
    void nullSideKeepsOriginalInstance() {
        EvidenceFact evidence = EvidenceFact.builder()
                .id("evidence-4")
                .type(EvidenceType.AST)
                .build();

        assertSame(
                evidence,
                EvidenceMergePolicy.merge(evidence, null)
        );
        assertSame(
                evidence,
                EvidenceMergePolicy.merge(null, evidence)
        );
    }
}
