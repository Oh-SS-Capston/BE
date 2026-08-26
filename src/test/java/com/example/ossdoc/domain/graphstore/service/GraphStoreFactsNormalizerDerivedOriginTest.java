package com.example.ossdoc.domain.graphstore.service;

import com.example.ossdoc.domain.graphstore.dto.facts.raw.RawFactsDocumentDto;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedFactsDocument;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedRelationFact;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * symbol의 origin과 relation의 origin은 서로 다른 값 집합이다.
 *
 *   symbol   : ast, bytecode, generated, ast_and_bytecode
 *   relation : ast, bytecode, ast_and_bytecode(merged), contract, derived, observed, resource
 *
 * symbol origin을 relation origin으로 그대로 넘기면 generated에서 edge 변환이 터진다.
 *   IllegalArgumentException: Unsupported relation origin: generated
 * record를 쓰는 저장소(JUnit)에서 record 컴포넌트 필드가 origin=generated로 수집되어
 * GRAPHSTORE 단계 전체가 죽은 이력이 있다.
 */
class GraphStoreFactsNormalizerDerivedOriginTest {

    /** FactsEdgeConverter.toOriginKind가 받아들이는 relation origin 값. */
    private static final Set<String> SUPPORTED_RELATION_ORIGINS = Set.of(
            "ast",
            "bytecode",
            "ast_and_bytecode",
            "merged",
            "contract",
            "derived",
            "observed",
            "resource"
    );

    private final ObjectMapper objectMapper =
            new ObjectMapper().findAndRegisterModules();

    private final GraphStoreFactsNormalizer normalizer =
            new GraphStoreFactsNormalizer();

    @Test
    @DisplayName("origin=generated 심볼에서 파생된 관계는 derived origin으로 변환된다")
    void mapsGeneratedSymbolOriginToDerived() throws Exception {
        NormalizedFactsDocument normalized = normalize();

        // record 컴포넌트(origin=generated)에서 파생된 HAS_FIELD
        assertEquals(
                "derived",
                originOf(
                        normalized.relations(),
                        "HAS_FIELD",
                        "field:sample.Pair#left"
                ),
                "generated 심볼에서 파생된 HAS_FIELD는 derived여야 한다"
        );

        // 같은 심볼에서 파생된 CONTAINS
        assertEquals(
                "derived",
                originOf(
                        normalized.relations(),
                        "CONTAINS",
                        "type:sample.Pair"
                ),
                "generated 심볼에서 파생된 CONTAINS는 derived여야 한다"
        );
    }

    @Test
    @DisplayName("ast 심볼의 origin은 변환 없이 보존된다")
    void preservesAstSymbolOrigin() throws Exception {
        NormalizedFactsDocument normalized = normalize();

        assertEquals(
                "ast",
                originOf(
                        normalized.relations(),
                        "HAS_FIELD",
                        "field:sample.Holder#value"
                ),
                "ast 심볼의 origin까지 derived로 뭉개면 안 된다"
        );
    }

    @Test
    @DisplayName("파생된 모든 관계의 origin이 edge 변환이 받는 값 집합 안에 있다")
    void allDerivedOriginsAreConvertible() throws Exception {
        List<NormalizedRelationFact> relations = normalize().relations();

        assertTrue(relations.size() > 0, "파생 관계가 하나도 없으면 검증 의미가 없다");

        for (NormalizedRelationFact relation : relations) {
            assertTrue(
                    relation.origin() != null
                            && SUPPORTED_RELATION_ORIGINS.contains(relation.origin()),
                    () -> "edge 변환이 거부하는 origin이 생성됨: "
                            + relation.kind() + " origin=" + relation.origin()
            );
        }
    }

    private NormalizedFactsDocument normalize() throws Exception {
        RawFactsDocumentDto raw = objectMapper.readValue(
                factsJson(),
                RawFactsDocumentDto.class
        );
        return normalizer.normalize(raw);
    }

    private String originOf(
            List<NormalizedRelationFact> relations,
            String kind,
            String srcSymbol
    ) {
        return relations.stream()
                .filter(relation -> kind.equalsIgnoreCase(relation.kind()))
                .filter(relation -> srcSymbol.equals(relation.srcSymbol()))
                .map(NormalizedRelationFact::origin)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        kind + " 관계를 찾지 못함: " + srcSymbol
                ));
    }

    /**
     * sample.Pair#left  : record 컴포넌트 → origin=generated
     * sample.Holder#value: 일반 필드      → origin=ast
     */
    private String factsJson() {
        return """
                {
                  "schema_version": "0.1",
                  "evidence": [],
                  "symbols": {
                    "modules": [],
                    "packages": [],
                    "types": [
                      {
                        "symbol": "type:sample.Pair",
                        "name": "Pair",
                        "kind": "type",
                        "type_kind": "record",
                        "qualified_name": "sample.Pair",
                        "package_symbol": "package:sample",
                        "origin": "ast",
                        "modifiers": [],
                        "evidence_ids": []
                      },
                      {
                        "symbol": "type:sample.Holder",
                        "name": "Holder",
                        "kind": "type",
                        "type_kind": "class",
                        "qualified_name": "sample.Holder",
                        "package_symbol": "package:sample",
                        "origin": "ast",
                        "modifiers": [],
                        "evidence_ids": []
                      },
                      {
                        "symbol": "type:sample.Target",
                        "name": "Target",
                        "kind": "type",
                        "type_kind": "class",
                        "qualified_name": "sample.Target",
                        "package_symbol": "package:sample",
                        "origin": "ast",
                        "modifiers": [],
                        "evidence_ids": []
                      }
                    ],
                    "constructors": [],
                    "methods": [],
                    "fields": [
                      {
                        "symbol": "field:sample.Pair#left",
                        "name": "left",
                        "kind": "field",
                        "owner_symbol": "type:sample.Pair",
                        "owner_type_symbol": "type:sample.Pair",
                        "origin": "generated",
                        "modifiers": ["final"],
                        "evidence_ids": [],
                        "signature": {
                          "field_type": {
                            "raw": "sample.Target",
                            "primitive": false,
                            "array_dim": 0,
                            "unresolved": false
                          }
                        }
                      },
                      {
                        "symbol": "field:sample.Holder#value",
                        "name": "value",
                        "kind": "field",
                        "owner_symbol": "type:sample.Holder",
                        "owner_type_symbol": "type:sample.Holder",
                        "origin": "ast",
                        "modifiers": [],
                        "evidence_ids": [],
                        "signature": {
                          "field_type": {
                            "raw": "sample.Target",
                            "primitive": false,
                            "array_dim": 0,
                            "unresolved": false
                          }
                        }
                      }
                    ]
                  },
                  "relations": {},
                  "observations": {}
                }
                """;
    }
}
