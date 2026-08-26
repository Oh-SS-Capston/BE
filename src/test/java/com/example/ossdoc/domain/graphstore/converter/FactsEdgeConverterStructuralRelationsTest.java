package com.example.ossdoc.domain.graphstore.converter;

import com.example.ossdoc.domain.graphstore.entity.Edge;
import com.example.ossdoc.domain.graphstore.entity.SymbolEntity;
import com.example.ossdoc.domain.graphstore.enums.DerivationKind;
import com.example.ossdoc.domain.graphstore.enums.EdgeType;
import com.example.ossdoc.domain.graphstore.enums.OriginKind;
import com.example.ossdoc.domain.graphstore.enums.ResolutionStatus;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedRelationFact;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class FactsEdgeConverterStructuralRelationsTest {

    private final FactsEdgeConverter converter =
            new FactsEdgeConverter(
                    new ObjectMapper()
                            .findAndRegisterModules()
            );

    @Test
    @DisplayName("구조 관계 7종을 대응하는 EdgeType으로 변환한다")
    void toEntity_convertsAllStructuralRelationKinds() {
        RepoRun run =
                mock(RepoRun.class);

        SymbolEntity fromSymbol =
                mock(SymbolEntity.class);

        SymbolEntity toSymbol =
                mock(SymbolEntity.class);

        Map<String, EdgeType> expectedMappings =
                new LinkedHashMap<>();

        expectedMappings.put(
                "CONTAINS",
                EdgeType.CONTAINS
        );

        expectedMappings.put(
                "EXTENDS",
                EdgeType.EXTENDS
        );

        expectedMappings.put(
                "IMPLEMENTS",
                EdgeType.IMPLEMENTS
        );

        expectedMappings.put(
                "HAS_FIELD",
                EdgeType.HAS_FIELD
        );

        expectedMappings.put(
                "RETURNS",
                EdgeType.RETURNS
        );

        expectedMappings.put(
                "PARAM",
                EdgeType.PARAM
        );

        expectedMappings.put(
                "THROWS",
                EdgeType.THROWS
        );

        for (Map.Entry<String, EdgeType> entry
                : expectedMappings.entrySet()) {

            NormalizedRelationFact relation =
                    structuralRelation(
                            entry.getKey(),
                            "type:sample.Source",
                            "type:sample.Target"
                    );

            Edge edge =
                    converter.toEntity(
                            run,
                            relation,
                            fromSymbol,
                            toSymbol
                    );

            assertEquals(
                    entry.getValue(),
                    edge.getEdgeType(),
                    entry.getKey()
                            + " 관계의 EdgeType 변환이 잘못됨"
            );

            assertSame(
                    run,
                    edge.getRun(),
                    entry.getKey()
                            + " 관계의 RepoRun이 보존되어야 함"
            );

            assertSame(
                    fromSymbol,
                    edge.getFromSymbol(),
                    entry.getKey()
                            + " 관계의 출발 심볼이 보존되어야 함"
            );

            assertSame(
                    toSymbol,
                    edge.getToSymbol(),
                    entry.getKey()
                            + " 관계의 도착 심볼이 보존되어야 함"
            );

            assertNull(
                    edge.getToRawRef(),
                    entry.getKey()
                            + " 관계의 목적지 심볼이 존재하면 "
                            + "toRawRef가 없어야 함"
            );

            assertEquals(
                    OriginKind.AST,
                    edge.getOrigin(),
                    entry.getKey()
                            + " 관계의 origin이 AST여야 함"
            );

            assertEquals(
                    DerivationKind.DERIVED,
                    edge.getDerivationKind(),
                    entry.getKey()
                            + " 관계의 derivation이 DERIVED여야 함"
            );

            assertEquals(
                    ResolutionStatus.RESOLVED,
                    edge.getResolution(),
                    entry.getKey()
                            + " 관계의 목적지 심볼이 존재하면 "
                            + "RESOLVED여야 함"
            );

            assertEquals(
                    new BigDecimal("0.9000"),
                    edge.getConfidence()
                            .setScale(4),
                    entry.getKey()
                            + " 관계의 confidence가 보존되어야 함"
            );

            assertNull(
                    edge.getAttrs(),
                    entry.getKey()
                            + " 관계의 빈 attrs는 null로 변환되어야 함"
            );
        }
    }

    @Test
    @DisplayName("목적지 심볼이 없으면 구조 관계를 미해결 참조로 보존한다")
    void toEntity_preservesUnresolvedStructuralDestination() {
        RepoRun run =
                mock(RepoRun.class);

        SymbolEntity fromSymbol =
                mock(SymbolEntity.class);

        NormalizedRelationFact relation =
                structuralRelation(
                        "EXTENDS",
                        "type:sample.Child",
                        "type:missing.Parent"
                );

        Edge edge =
                converter.toEntity(
                        run,
                        relation,
                        fromSymbol,
                        null
                );

        assertEquals(
                EdgeType.EXTENDS,
                edge.getEdgeType()
        );

        assertNull(
                edge.getToSymbol(),
                "찾지 못한 목적지 심볼은 null이어야 함"
        );

        assertEquals(
                ResolutionStatus.UNRESOLVED,
                edge.getResolution(),
                "목적지 심볼을 찾지 못하면 UNRESOLVED여야 함"
        );

        JsonNode toRawRef =
                edge.getToRawRef();

        assertTrue(
                toRawRef != null
                        && toRawRef.isObject(),
                "미해결 목적지는 toRawRef 객체로 보존되어야 함"
        );

        assertEquals(
                "type:missing.Parent",
                toRawRef.path("raw").asText()
        );

        assertTrue(
                toRawRef.path("unresolved").asBoolean()
        );

        assertFalse(
                toRawRef.has("resolved"),
                "미해결 참조에 resolved 필드가 추가되면 안 됨"
        );

        assertEquals(
                OriginKind.AST,
                edge.getOrigin()
        );

        assertEquals(
                DerivationKind.DERIVED,
                edge.getDerivationKind()
        );
    }

    private NormalizedRelationFact structuralRelation(
            String kind,
            String srcSymbol,
            String dstSymbol
    ) {
        return new NormalizedRelationFact(
                kind,
                srcSymbol,
                dstSymbol,
                null,
                "ast",
                "derived",
                null,
                null,
                null,
                new BigDecimal("0.9"),
                Map.of(),
                List.of()
        );
    }
}