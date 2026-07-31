package com.example.ossdoc.domain.extraction.service.composer;

import com.example.ossdoc.domain.extraction.dto.model.ObservationFact;
import com.example.ossdoc.domain.extraction.dto.model.TypeRef;
import com.example.ossdoc.domain.extraction.enums.FactOriginKind;
import com.example.ossdoc.domain.extraction.enums.ObservationKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FactsDedupSupportObservationTest {

    @Test
    @DisplayName("Composer dedup — AST와 ASM 타입 표현 차이를 무시하고 observation 병합")
    void observationKey_usesSemanticTypeIdentity() {
        ObservationFact ast = provider(
                FactOriginKind.AST,
                TypeRef.builder()
                        .raw("sample.Service")
                        .arrayDim(0)
                        .primitive(false)
                        .unresolved(false)
                        .sourceText("Service")
                        .build(),
                "ast-evidence",
                Map.of("ast", true)
        );

        ObservationFact bytecode = provider(
                FactOriginKind.BYTECODE,
                TypeRef.builder()
                        .raw("sample.Service")
                        .arrayDim(0)
                        .primitive(false)
                        .unresolved(false)
                        .sourceText(null)
                        .build(),
                "bytecode-evidence",
                Map.of("bytecode", true)
        );

        assertEquals(
                FactsDedupSupport.observationKey(ast),
                FactsDedupSupport.observationKey(bytecode),
                "sourceText 차이는 observation identity에 포함되면 안 됨"
        );

        ObservationFact merged =
                FactsDedupSupport.mergeObservation(ast, bytecode);

        assertEquals(
                FactOriginKind.AST_AND_BYTECODE,
                merged.origin()
        );
        assertEquals(2, merged.evidenceIds().size());
        assertTrue(Boolean.TRUE.equals(merged.attrs().get("ast")));
        assertTrue(Boolean.TRUE.equals(merged.attrs().get("bytecode")));
    }

    private ObservationFact provider(
            FactOriginKind origin,
            TypeRef targetType,
            String evidenceId,
            Map<String, Object> attrs
    ) {
        return ObservationFact.builder()
                .kind(ObservationKind.DI_PROVIDER)
                .siteSymbol("method:sample.Config#service()")
                .targetTypeRef(targetType)
                .note(origin == FactOriginKind.AST
                        ? "provider from AST"
                        : "provider from bytecode")
                .evidenceIds(List.of(evidenceId))
                .origin(origin)
                .confidenceHint(origin == FactOriginKind.AST ? 0.7 : 0.9)
                .attrs(attrs)
                .build();
    }
}
