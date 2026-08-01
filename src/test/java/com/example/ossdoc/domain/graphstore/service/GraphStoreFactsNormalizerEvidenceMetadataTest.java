package com.example.ossdoc.domain.graphstore.service;

import com.example.ossdoc.domain.graphstore.dto.facts.raw.RawEvidenceFactDto;
import com.example.ossdoc.domain.graphstore.dto.facts.raw.RawFactsDocumentDto;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedEvidenceFact;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedFactsDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GraphStoreFactsNormalizerEvidenceMetadataTest {

    private final GraphStoreFactsNormalizer normalizer =
            new GraphStoreFactsNormalizer();

    @Test
    @DisplayName("Evidence의 column·symbol·role·granularity attrs를 정규화 모델까지 보존한다")
    void preservesEvidenceMetadata() {
        RawEvidenceFactDto rawEvidence =
                new RawEvidenceFactDto();

        rawEvidence.setId("evidence-role-1");
        rawEvidence.setType("AST");
        rawEvidence.setPath(
                "src/main/java/sample/Sample.java"
        );
        rawEvidence.setStartLine(10);
        rawEvidence.setStartCol(9);
        rawEvidence.setEndLine(10);
        rawEvidence.setEndCol(21);
        rawEvidence.setSymbol(
                "method:sample.Sample#run()"
        );
        rawEvidence.setSnippet("service.call()");
        rawEvidence.setHash("hash-1");
        rawEvidence.setAttrs(Map.of(
                "granularity", "expression",
                "role", "method_call",
                "instruction_index", 7
        ));

        RawFactsDocumentDto raw =
                new RawFactsDocumentDto();

        raw.setSchemaVersion("2");
        raw.setEvidence(List.of(rawEvidence));

        NormalizedFactsDocument normalized =
                normalizer.normalize(raw);

        NormalizedEvidenceFact evidence =
                normalized.evidence()
                        .get("evidence-role-1");

        assertNotNull(evidence);
        assertEquals(10, evidence.startLine());
        assertEquals(9, evidence.startCol());
        assertEquals(10, evidence.endLine());
        assertEquals(21, evidence.endCol());
        assertEquals(
                "method:sample.Sample#run()",
                evidence.symbol()
        );
        assertEquals(
                "expression",
                evidence.attrs().get("granularity")
        );
        assertEquals(
                "method_call",
                evidence.attrs().get("role")
        );
        assertEquals(
                7,
                evidence.attrs().get("instruction_index")
        );
    }
}
