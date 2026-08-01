package com.example.ossdoc.domain.graphstore.converter;

import com.example.ossdoc.domain.graphstore.entity.Edge;
import com.example.ossdoc.domain.graphstore.entity.SymbolEntity;
import com.example.ossdoc.domain.graphstore.enums.DerivationKind;
import com.example.ossdoc.domain.graphstore.enums.EdgeType;
import com.example.ossdoc.domain.graphstore.enums.OriginKind;
import com.example.ossdoc.domain.graphstore.enums.ResolutionStatus;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedRelationFact;
import com.example.ossdoc.domain.graphstore.model.promotion.ObservationPromotionContract;
import com.example.ossdoc.domain.graphstore.model.promotion.ObservationPromotionContractCatalog;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

class FactsEdgeConverterSemanticRelationsTest {

    private final FactsEdgeConverter converter =
            new FactsEdgeConverter(
                    new ObjectMapper()
                            .findAndRegisterModules()
            );

    @Test
    @DisplayName("Observation 승격 계약의 모든 relationKind를 GraphStore EdgeType으로 변환한다")
    void convertsEveryPromotedSemanticRelationKind() {
        Map<String, EdgeType> expected =
                new LinkedHashMap<>();

        expected.put(
                "handles_endpoint",
                EdgeType.HANDLES_ENDPOINT
        );
        expected.put(
                "declares_bean",
                EdgeType.DECLARES_BEAN
        );
        expected.put(
                "configures_bean",
                EdgeType.CONFIGURES_BEAN
        );
        expected.put(
                "injects",
                EdgeType.INJECTS
        );
        expected.put(
                "publishes_event",
                EdgeType.PUBLISHES_EVENT
        );
        expected.put(
                "listens_event",
                EdgeType.LISTENS_EVENT
        );
        expected.put(
                "provides_spi",
                EdgeType.PROVIDES_SPI
        );
        expected.put(
                "loads_service",
                EdgeType.LOADS_SERVICE
        );
        expected.put(
                "reflects_type",
                EdgeType.REFLECTS_TYPE
        );
        expected.put(
                "reflects_method",
                EdgeType.REFLECTS_METHOD
        );
        expected.put(
                "reflects_field",
                EdgeType.REFLECTS_FIELD
        );
        expected.put(
                "reflects_constructor",
                EdgeType.REFLECTS_CONSTRUCTOR
        );

        RepoRun run = mock(RepoRun.class);
        SymbolEntity source = mock(SymbolEntity.class);

        for (Map.Entry<String, EdgeType> entry
                : expected.entrySet()) {
            Edge edge = converter.toEntity(
                    run,
                    semanticRelation(entry.getKey()),
                    source,
                    null
            );

            assertEquals(
                    entry.getValue(),
                    edge.getEdgeType(),
                    entry.getKey()
            );

            assertSame(run, edge.getRun());
            assertSame(source, edge.getFromSymbol());
            assertNull(edge.getToSymbol());

            assertEquals(
                    OriginKind.OBSERVED,
                    edge.getOrigin()
            );

            assertEquals(
                    DerivationKind.DERIVED,
                    edge.getDerivationKind()
            );

            assertEquals(
                    ResolutionStatus.PARTIAL,
                    edge.getResolution()
            );

            assertEquals(
                    new BigDecimal("0.7000"),
                    edge.getConfidence()
                            .setScale(4)
            );

            assertEquals(
                    entry.getKey(),
                    edge.getAttrs()
                            .path("semantic_kind")
                            .asText()
            );
        }
    }

    @Test
    @DisplayName("승격 계약 카탈로그의 relationKind 집합과 converter 지원 집합이 일치한다")
    void converterCoversCatalogRelationKinds() {
        for (ObservationPromotionContract contract
                : ObservationPromotionContractCatalog.all()) {
            for (String relationKind
                    : contract.relationKinds()) {
                Edge edge = converter.toEntity(
                        mock(RepoRun.class),
                        semanticRelation(relationKind),
                        mock(SymbolEntity.class),
                        null
                );

                assertEquals(
                        relationKind.toUpperCase(Locale.ROOT),
                        edge.getEdgeType().name()
                );
            }
        }
    }

    private NormalizedRelationFact semanticRelation(
            String kind
    ) {
        return new NormalizedRelationFact(
                kind,
                "method:sample.Source#run()",
                null,
                "semantic-target:" + kind,
                "observed",
                "derived",
                "partial",
                "shadow contract fixture",
                31,
                new BigDecimal("0.7"),
                Map.of(
                        "semantic_kind", kind,
                        "resolver", "ContractFixtureResolver",
                        "resolution_basis", "inferred",
                        "confidence_band", "medium",
                        "default_visible", true
                ),
                List.of("evidence-1")
        );
    }
}
