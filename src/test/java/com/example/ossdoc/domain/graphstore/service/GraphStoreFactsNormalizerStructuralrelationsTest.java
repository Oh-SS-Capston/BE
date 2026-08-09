package com.example.ossdoc.domain.graphstore.service;

import com.example.ossdoc.domain.graphstore.dto.facts.raw.RawFactsDocumentDto;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedFactsDocument;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedRelationFact;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedSymbolFact;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphStoreFactsNormalizerStructuralRelationsTest {

    private static final String SAMPLE_PACKAGE =
            "package:sample";

    private static final String STRUCTURAL_TYPE =
            "type:sample.StructuralSample";

    private static final String PARENT_TYPE =
            "type:sample.ParentType";

    private static final String PORT_TYPE =
            "type:sample.SamplePort";

    private static final String TARGET_TYPE =
            "type:sample.Target";

    private static final String EXCEPTION_TYPE =
            "type:sample.SampleException";

    private static final String FIELD_SYMBOL =
            "field:sample.StructuralSample#field";

    private static final String METHOD_SYMBOL =
            "method:sample.StructuralSample#execute(sample.Target,int)";

    private final ObjectMapper objectMapper =
            new ObjectMapper()
                    .findAndRegisterModules();

    private final GraphStoreFactsNormalizer normalizer =
            new GraphStoreFactsNormalizer();

    @Test
    @DisplayName("심볼 구조 정보로 구조 관계 7종을 파생한다")
    void normalize_derivesAllStructuralRelations()
            throws Exception {

        RawFactsDocumentDto raw =
                objectMapper.readValue(
                        structuralFactsJson(),
                        RawFactsDocumentDto.class
                );

        NormalizedFactsDocument normalized =
                normalizer.normalize(raw);

        assertNotNull(normalized);
        assertNotNull(normalized.symbols());
        assertNotNull(normalized.relations());

        /*
         * CONTAINS:
         * package → type
         */
        assertDerivedRelation(
                normalized.relations(),
                "CONTAINS",
                SAMPLE_PACKAGE,
                STRUCTURAL_TYPE
        );

        /*
         * CONTAINS:
         * owner type → field
         */
        assertDerivedRelation(
                normalized.relations(),
                "CONTAINS",
                STRUCTURAL_TYPE,
                FIELD_SYMBOL
        );

        /*
         * CONTAINS:
         * owner type → method
         */
        assertDerivedRelation(
                normalized.relations(),
                "CONTAINS",
                STRUCTURAL_TYPE,
                METHOD_SYMBOL
        );

        assertDerivedRelation(
                normalized.relations(),
                "EXTENDS",
                STRUCTURAL_TYPE,
                PARENT_TYPE
        );

        assertDerivedRelation(
                normalized.relations(),
                "IMPLEMENTS",
                STRUCTURAL_TYPE,
                PORT_TYPE
        );

        assertDerivedRelation(
                normalized.relations(),
                "HAS_FIELD",
                FIELD_SYMBOL,
                TARGET_TYPE
        );

        assertDerivedRelation(
                normalized.relations(),
                "RETURNS",
                METHOD_SYMBOL,
                TARGET_TYPE
        );

        assertDerivedRelation(
                normalized.relations(),
                "PARAM",
                METHOD_SYMBOL,
                TARGET_TYPE
        );

        assertDerivedRelation(
                normalized.relations(),
                "THROWS",
                METHOD_SYMBOL,
                EXCEPTION_TYPE
        );

        /*
         * int 같은 원시 타입은 PARAM 관계로 만들지 않는다.
         */
        assertFalse(
                containsRelation(
                        normalized.relations(),
                        "PARAM",
                        METHOD_SYMBOL,
                        "type:int"
                ),
                "원시 타입 int는 PARAM 관계로 생성되면 안 됨"
        );

        assertFalse(
                containsRelation(
                        normalized.relations(),
                        "PARAM",
                        METHOD_SYMBOL,
                        "int"
                ),
                "원시 타입 int는 PARAM 관계로 생성되면 안 됨"
        );

        assertPackageVirtualSymbol(normalized);
    }

    private void assertPackageVirtualSymbol(
            NormalizedFactsDocument normalized
    ) {
        NormalizedSymbolFact packageSymbol =
                normalized.symbols()
                        .stream()
                        .filter(symbol ->
                                SAMPLE_PACKAGE.equals(
                                        symbol.symbol()
                                )
                        )
                        .findFirst()
                        .orElse(null);

        assertNotNull(
                packageSymbol,
                "입력에 없는 package 가상 심볼이 생성되어야 함"
        );

        assertEquals(
                "package",
                packageSymbol.kind(),
                "가상 패키지 심볼 kind가 package여야 함"
        );

        assertEquals(
                "derived",
                packageSymbol.origin(),
                "가상 패키지 심볼 origin이 derived여야 함"
        );
    }

    private void assertDerivedRelation(
            List<NormalizedRelationFact> relations,
            String kind,
            String srcSymbol,
            String dstSymbol
    ) {
        List<NormalizedRelationFact> matches =
                relations.stream()
                        .filter(relation ->
                                kind.equalsIgnoreCase(
                                        relation.kind()
                                )
                        )
                        .filter(relation ->
                                srcSymbol.equals(
                                        relation.srcSymbol()
                                )
                        )
                        .filter(relation ->
                                dstSymbol.equals(
                                        relation.dstSymbol()
                                )
                        )
                        .toList();

        assertEquals(
                1,
                matches.size(),
                () -> kind
                        + " 관계가 정확히 한 건 존재해야 함: "
                        + srcSymbol
                        + " → "
                        + dstSymbol
                        + "\n실제 "
                        + kind
                        + " 관계: "
                        + describeRelations(
                        relations,
                        kind
                )
        );

        NormalizedRelationFact relation =
                matches.get(0);

        assertEquals(
                "derived",
                relation.derivation(),
                kind
                        + " 관계 derivation이 derived여야 함"
        );

        assertEquals(
                "ast",
                relation.origin(),
                kind
                        + " 관계가 원본 심볼의 AST origin을 보존해야 함"
        );

        assertTrue(
                relation.evidenceIds() == null
                        || relation.evidenceIds()
                        .isEmpty(),
                kind
                        + " 파생 관계는 현재 직접 Evidence가 없어야 함"
        );
    }

    private boolean containsRelation(
            List<NormalizedRelationFact> relations,
            String kind,
            String srcSymbol,
            String dstSymbol
    ) {
        return relations.stream()
                .anyMatch(relation ->
                        kind.equalsIgnoreCase(
                                relation.kind()
                        )
                                && srcSymbol.equals(
                                relation.srcSymbol()
                        )
                                && dstSymbol.equals(
                                relation.dstSymbol()
                        )
                );
    }

    private String describeRelations(
            List<NormalizedRelationFact> relations,
            String kind
    ) {
        return relations.stream()
                .filter(relation ->
                        kind.equalsIgnoreCase(
                                relation.kind()
                        )
                )
                .map(relation ->
                        relation.srcSymbol()
                                + " → "
                                + relation.dstSymbol()
                )
                .toList()
                .toString();
    }

    private String structuralFactsJson() {
        return """
                {
                  "schema_version": "0.1",
                  "evidence": [],
                  "symbols": {
                    "modules": [],
                    "packages": [],
                    "types": [
                      {
                        "symbol": "type:sample.ParentType",
                        "name": "ParentType",
                        "kind": "type",
                        "type_kind": "class",
                        "qualified_name": "sample.ParentType",
                        "package_symbol": "package:sample",
                        "origin": "ast",
                        "modifiers": [],
                        "evidence_ids": []
                      },
                      {
                        "symbol": "type:sample.SamplePort",
                        "name": "SamplePort",
                        "kind": "type",
                        "type_kind": "interface",
                        "qualified_name": "sample.SamplePort",
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
                      },
                      {
                        "symbol": "type:sample.SampleException",
                        "name": "SampleException",
                        "kind": "type",
                        "type_kind": "class",
                        "qualified_name": "sample.SampleException",
                        "package_symbol": "package:sample",
                        "origin": "ast",
                        "modifiers": [],
                        "evidence_ids": []
                      },
                      {
                        "symbol": "type:sample.StructuralSample",
                        "name": "StructuralSample",
                        "kind": "type",
                        "type_kind": "class",
                        "qualified_name": "sample.StructuralSample",
                        "package_symbol": "package:sample",
                        "origin": "ast",
                        "modifiers": [],
                        "evidence_ids": [],
                        "super_type_ref": {
                          "raw": "sample.ParentType",
                          "primitive": false,
                          "array_dim": 0,
                          "unresolved": false
                        },
                        "interface_type_refs": [
                          {
                            "raw": "sample.SamplePort",
                            "primitive": false,
                            "array_dim": 0,
                            "unresolved": false
                          }
                        ]
                      }
                    ],
                    "constructors": [],
                    "methods": [
                      {
                        "symbol": "method:sample.StructuralSample#execute(sample.Target,int)",
                        "name": "execute",
                        "kind": "method",
                        "owner_symbol": "type:sample.StructuralSample",
                        "owner_type_symbol": "type:sample.StructuralSample",
                        "origin": "ast",
                        "modifiers": [],
                        "evidence_ids": [],
                        "signature": {
                          "params": [
                            {
                              "name": "input",
                              "type_ref": {
                                "raw": "sample.Target",
                                "primitive": false,
                                "array_dim": 0,
                                "unresolved": false
                              }
                            },
                            {
                              "name": "count",
                              "type_ref": {
                                "raw": "int",
                                "primitive": true,
                                "array_dim": 0,
                                "unresolved": false
                              }
                            }
                          ],
                          "returns": {
                            "raw": "sample.Target",
                            "primitive": false,
                            "array_dim": 0,
                            "unresolved": false
                          },
                          "throws": [
                            {
                              "raw": "sample.SampleException",
                              "primitive": false,
                              "array_dim": 0,
                              "unresolved": false
                            }
                          ]
                        }
                      }
                    ],
                    "fields": [
                      {
                        "symbol": "field:sample.StructuralSample#field",
                        "name": "field",
                        "kind": "field",
                        "owner_symbol": "type:sample.StructuralSample",
                        "owner_type_symbol": "type:sample.StructuralSample",
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