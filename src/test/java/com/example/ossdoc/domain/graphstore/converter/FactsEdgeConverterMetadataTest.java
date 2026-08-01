package com.example.ossdoc.domain.graphstore.converter;

import com.example.ossdoc.domain.graphstore.entity.Edge;
import com.example.ossdoc.domain.graphstore.entity.SymbolEntity;
import com.example.ossdoc.domain.graphstore.enums.DerivationKind;
import com.example.ossdoc.domain.graphstore.enums.EdgeType;
import com.example.ossdoc.domain.graphstore.enums.OriginKind;
import com.example.ossdoc.domain.graphstore.enums.ResolutionStatus;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedRelationFact;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class FactsEdgeConverterMetadataTest {

    private final FactsEdgeConverter converter =
            new FactsEdgeConverter(
                    new ObjectMapper()
                            .findAndRegisterModules()
            );

    @Test
    @DisplayName("Relation의 callSiteLine과 정책 attrs를 Edge에 보존한다")
    void preservesCallSiteLineAndAttrs() {
        NormalizedRelationFact relation =
                new NormalizedRelationFact(
                        "CALLS",
                        "method:sample.A#run()",
                        "method:sample.B#call()",
                        null,
                        "ast_and_bytecode",
                        "direct",
                        "resolved",
                        "symbol solver and bytecode agree",
                        42,
                        new BigDecimal("0.98"),
                        Map.of(
                                "resolution_basis",
                                "ast_and_bytecode",
                                "confidence_band",
                                "high",
                                "default_visible",
                                true
                        ),
                        List.of(
                                "ast-evidence",
                                "bytecode-evidence"
                        )
                );

        Edge edge = converter.toEntity(
                mock(RepoRun.class),
                relation,
                mock(SymbolEntity.class),
                mock(SymbolEntity.class)
        );

        assertEquals(EdgeType.CALLS, edge.getEdgeType());
        assertEquals(
                OriginKind.MERGED,
                edge.getOrigin()
        );
        assertEquals(
                DerivationKind.DIRECT,
                edge.getDerivationKind()
        );
        assertEquals(
                ResolutionStatus.RESOLVED,
                edge.getResolution()
        );
        assertEquals(42, edge.getCallSiteLine());
        assertEquals(
                new BigDecimal("0.98"),
                edge.getConfidence()
        );
        assertEquals(
                "ast_and_bytecode",
                edge.getAttrs()
                        .path("resolution_basis")
                        .asText()
        );
        assertEquals(
                "high",
                edge.getAttrs()
                        .path("confidence_band")
                        .asText()
        );
        assertEquals(
                true,
                edge.getAttrs()
                        .path("default_visible")
                        .asBoolean()
        );
    }
}
