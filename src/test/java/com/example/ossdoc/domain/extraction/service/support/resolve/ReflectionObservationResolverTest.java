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
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReflectionObservationResolverTest {

    private final ReflectionObservationResolver resolver =
            new ReflectionObservationResolver();

    @Test
    @DisplayName("Reflection observation을 타입·메서드·필드·생성자 관계로 분리한다")
    void resolvesReflectionRelations() {
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
                contextOf(List.of(type, method, field, constructor))
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
            assertEquals(ResolutionStatus.RESOLVED, relation.resolution().status());
            assertEquals(DerivationKind.DERIVED, relation.derivation());
            assertEquals(FactOriginKind.AST, relation.origin());
            assertEquals(
                    "ReflectionObservationResolver",
                    relation.attrs().get("resolver")
            );
        }
    }

    @Test
    @DisplayName("invoke처럼 대상 멤버를 알 수 없는 reflection은 PARTIAL로 보존한다")
    void keepsUnknownInvocationAsPartial() {
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
                contextOf(List.of(invocation))
        );

        RelationFact relation = result.relations().get(0);
        assertEquals(RelationKind.REFLECTS_METHOD, relation.kind());
        assertEquals(ResolutionStatus.PARTIAL, relation.resolution().status());
        assertTrue(relation.dstRawRef().contains("unresolved-reflection"));
        assertEquals(List.of("ev-invoke"), relation.evidenceIds());
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
            List<ObservationFact> observations
    ) {
        SymbolTable symbols = SymbolTable.builder()
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

        return ObservationResolutionContext.from(
                ExtractionAggregate.builder()
                        .symbols(symbols)
                        .observations(ObservationTable.builder()
                                .reflectionSites(observations)
                                .build())
                        .build()
        );
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
