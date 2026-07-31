package com.example.ossdoc.domain.extraction.service.support.resolve;

import com.example.ossdoc.domain.extraction.dto.model.ExtractionAggregate;
import com.example.ossdoc.domain.extraction.dto.model.ObservationFact;
import com.example.ossdoc.domain.extraction.dto.model.ObservationTable;
import com.example.ossdoc.domain.extraction.dto.model.RelationFact;
import com.example.ossdoc.domain.extraction.dto.model.SymbolFact;
import com.example.ossdoc.domain.extraction.dto.model.SymbolTable;
import com.example.ossdoc.domain.extraction.enums.DerivationKind;
import com.example.ossdoc.domain.extraction.enums.FactOriginKind;
import com.example.ossdoc.domain.extraction.enums.ObservationKind;
import com.example.ossdoc.domain.extraction.enums.RelationKind;
import com.example.ossdoc.domain.extraction.enums.ResolutionStatus;
import com.example.ossdoc.domain.extraction.enums.SymbolKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReflectionObservationResolverTest {

    private final ReflectionObservationResolver resolver =
            new ReflectionObservationResolver();

    @Test
    @DisplayName("Reflection 관계 네 종류에 공통 Resolution·Confidence 정책을 적용한다")
    void resolvesReflectionRelationsWithSharedPolicy() {
        ObservationFact type = reflection(
                "method:sample.ReflectionClient#loadType()",
                Map.of(
                        "api_method", "forName",
                        "reflection_kind", "type",
                        "target_type", "sample.ReflectTarget"
                ),
                "ev-type"
        );
        ObservationFact method = reflection(
                "method:sample.ReflectionClient#loadMethod()",
                Map.of(
                        "api_method", "getDeclaredMethod",
                        "reflection_kind", "method",
                        "target_type", "sample.ReflectTarget",
                        "member_name", "run",
                        "parameter_types", List.of("java.lang.String")
                ),
                "ev-method"
        );
        ObservationFact field = reflection(
                "method:sample.ReflectionClient#loadField()",
                Map.of(
                        "api_method", "getDeclaredField",
                        "reflection_kind", "field",
                        "target_type", "sample.ReflectTarget",
                        "member_name", "value"
                ),
                "ev-field"
        );
        ObservationFact constructor = reflection(
                "method:sample.ReflectionClient#loadConstructor()",
                Map.of(
                        "api_method", "getDeclaredConstructor",
                        "reflection_kind", "constructor",
                        "target_type", "sample.ReflectTarget",
                        "parameter_types", List.of("java.lang.String")
                ),
                "ev-constructor"
        );

        ObservationResolutionResult result = resolver.resolve(
                contextOf(
                        List.of(type, method, field, constructor),
                        resolvedSymbols()
                )
        );

        assertTrue(result.warnings().isEmpty());
        assertEquals(4, result.relations().size());

        Map<RelationKind, RelationFact> byKind = result.relations().stream()
                .collect(Collectors.toMap(
                        RelationFact::kind,
                        relation -> relation
                ));

        assertEquals(
                "type:sample.ReflectTarget",
                byKind.get(RelationKind.REFLECTS_TYPE).dstSymbol()
        );
        assertEquals(
                "method:sample.ReflectTarget#run(java.lang.String)",
                byKind.get(RelationKind.REFLECTS_METHOD).dstSymbol()
        );
        assertEquals(
                "field:sample.ReflectTarget#value",
                byKind.get(RelationKind.REFLECTS_FIELD).dstSymbol()
        );
        assertEquals(
                "ctor:sample.ReflectTarget(java.lang.String)",
                byKind.get(RelationKind.REFLECTS_CONSTRUCTOR).dstSymbol()
        );

        for (RelationFact relation : result.relations()) {
            assertEquals(
                    ResolutionStatus.RESOLVED,
                    relation.resolution().status()
            );
            assertEquals(DerivationKind.DERIVED, relation.derivation());
            assertEquals(FactOriginKind.AST, relation.origin());
            assertEquals(0.923, relation.confidenceHint(), 0.0001);
            assertEquals(
                    "ReflectionObservationResolver",
                    relation.attrs().get("resolver")
            );
            assertEquals(
                    "exact_symbol",
                    relation.attrs().get("resolution_basis")
            );
            assertEquals(
                    "high",
                    relation.attrs().get("confidence_band")
            );
            assertEquals(true, relation.attrs().get("default_visible"));
        }
    }

    @Test
    @DisplayName("대상 타입과 멤버를 모두 알 수 없는 invoke는 UNRESOLVED로 보존한다")
    void keepsUnknownInvocationAsUnresolved() {
        ObservationFact invocation = reflection(
                "method:sample.ReflectionClient#invoke(java.lang.reflect.Method)",
                Map.of(
                        "api_method", "invoke",
                        "reflection_kind", "method",
                        "scope", "method"
                ),
                "ev-invoke"
        );

        ObservationResolutionResult result = resolver.resolve(
                contextOf(List.of(invocation), resolvedSymbols())
        );

        RelationFact relation = result.relations().get(0);
        assertEquals(RelationKind.REFLECTS_METHOD, relation.kind());
        assertEquals(
                ResolutionStatus.UNRESOLVED,
                relation.resolution().status()
        );
        assertTrue(relation.dstRawRef().contains("unresolved-reflection"));
        assertEquals(List.of("ev-invoke"), relation.evidenceIds());
        assertEquals(
                "unknown_target",
                relation.attrs().get("resolution_basis")
        );
        assertEquals(false, relation.attrs().get("default_visible"));
    }

    @Test
    @DisplayName("정적으로 알려진 외부 타입은 RAW_REFERENCE PARTIAL 관계로 유지한다")
    void keepsExternalStaticTypeAsRawReference() {
        ObservationFact observation = reflection(
                "method:sample.ReflectionClient#loadExternal()",
                Map.of(
                        "api_method", "forName",
                        "reflection_kind", "type",
                        "target_type", "external.lib.Plugin"
                ),
                "ev-external"
        );

        RelationFact relation = resolver.resolve(
                contextOf(List.of(observation), resolvedSymbols())
        ).relations().get(0);

        assertEquals(ResolutionStatus.PARTIAL, relation.resolution().status());
        assertEquals("type:external.lib.Plugin", relation.dstRawRef());
        assertEquals(
                "raw_reference",
                relation.attrs().get("resolution_basis")
        );
        assertEquals("medium", relation.attrs().get("confidence_band"));
        assertFalse((Boolean) relation.attrs().get("default_visible"));
    }

    @Test
    @DisplayName("동일 단순 이름의 타입 후보가 여러 개면 AMBIGUOUS_CANDIDATES로 처리한다")
    void marksAmbiguousTypeCandidates() {
        ObservationFact observation = reflection(
                "method:sample.ReflectionClient#loadAmbiguous()",
                Map.of(
                        "api_method", "forName",
                        "reflection_kind", "type",
                        "target_type", "SharedTarget"
                ),
                "ev-ambiguous"
        );

        SymbolTable symbols = SymbolTable.builder()
                .types(List.of(
                        symbol(
                                "type:sample.one.SharedTarget",
                                SymbolKind.TYPE,
                                "SharedTarget",
                                null,
                                "sample.one.SharedTarget"
                        ),
                        symbol(
                                "type:sample.two.SharedTarget",
                                SymbolKind.TYPE,
                                "SharedTarget",
                                null,
                                "sample.two.SharedTarget"
                        )
                ))
                .methods(List.of())
                .fields(List.of())
                .constructors(List.of())
                .build();

        RelationFact relation = resolver.resolve(
                contextOf(List.of(observation), symbols)
        ).relations().get(0);

        assertEquals(ResolutionStatus.PARTIAL, relation.resolution().status());
        assertEquals(
                "ambiguous_candidates",
                relation.attrs().get("resolution_basis")
        );
        assertEquals(2, relation.attrs().get("candidate_count"));
        assertEquals("medium", relation.attrs().get("confidence_band"));
        assertFalse((Boolean) relation.attrs().get("default_visible"));
    }

    private ObservationFact reflection(
            String siteSymbol,
            Map<String, Object> attrs,
            String evidenceId
    ) {
        return ObservationFact.builder()
                .kind(ObservationKind.REFLECTION_SITE)
                .siteSymbol(siteSymbol)
                .evidenceIds(List.of(evidenceId))
                .origin(FactOriginKind.AST)
                .confidenceHint(0.9)
                .attrs(attrs)
                .build();
    }

    private ObservationResolutionContext contextOf(
            List<ObservationFact> observations,
            SymbolTable symbols
    ) {
        return ObservationResolutionContext.from(
                ExtractionAggregate.builder()
                        .symbols(symbols)
                        .observations(ObservationTable.builder()
                                .reflectionSites(observations)
                                .build())
                        .build()
        );
    }

    private SymbolTable resolvedSymbols() {
        return SymbolTable.builder()
                .types(List.of(symbol(
                        "type:sample.ReflectTarget",
                        SymbolKind.TYPE,
                        "ReflectTarget",
                        null,
                        "sample.ReflectTarget"
                )))
                .methods(List.of(symbol(
                        "method:sample.ReflectTarget#run(java.lang.String)",
                        SymbolKind.METHOD,
                        "run",
                        "type:sample.ReflectTarget",
                        null
                )))
                .fields(List.of(symbol(
                        "field:sample.ReflectTarget#value",
                        SymbolKind.FIELD,
                        "value",
                        "type:sample.ReflectTarget",
                        null
                )))
                .constructors(List.of(symbol(
                        "ctor:sample.ReflectTarget(java.lang.String)",
                        SymbolKind.CONSTRUCTOR,
                        "ReflectTarget",
                        "type:sample.ReflectTarget",
                        null
                )))
                .build();
    }

    private SymbolFact symbol(
            String symbol,
            SymbolKind kind,
            String name,
            String ownerSymbol,
            String qualifiedName
    ) {
        return SymbolFact.builder()
                .symbol(symbol)
                .kind(kind)
                .name(name)
                .ownerSymbol(ownerSymbol)
                .qualifiedName(qualifiedName)
                .build();
    }
}
