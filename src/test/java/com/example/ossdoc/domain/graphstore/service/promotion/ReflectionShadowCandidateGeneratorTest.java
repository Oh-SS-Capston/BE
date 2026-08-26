package com.example.ossdoc.domain.graphstore.service.promotion;

import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedFactsDocument;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedObservationFact;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedRelationFact;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedSymbolFact;
import com.example.ossdoc.domain.graphstore.model.promotion.ObservationPromotionCandidateGenerationResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReflectionShadowCandidateGeneratorTest {

    private final ObjectMapper objectMapper =
            new ObjectMapper()
                    .findAndRegisterModules();

    @Test
    @DisplayName("정확한 내부 타입 symbol을 REFLECTS_TYPE 후보로 연결한다")
    void resolvesExactTypeSymbol() {
        NormalizedSymbolFact pluginType =
                symbol(
                        "type:sample.Plugin",
                        "Plugin",
                        "type",
                        "sample.Plugin",
                        null
                );

        ObjectNode attrs =
                JsonNodeFactory.instance
                        .objectNode();

        attrs.put(
                "reflection_kind",
                "type"
        );
        attrs.put(
                "api_method",
                "forName"
        );

        NormalizedRelationFact relation =
                generate(
                        List.of(pluginType),
                        List.of(observation(
                                "method:sample.Loader#load()",
                                "type:sample.Plugin",
                                null,
                                "ast",
                                attrs,
                                List.of("reflection-call")
                        ))
                ).candidates()
                        .get(0)
                        .relation();

        assertEquals(
                "reflects_type",
                relation.kind()
        );
        assertEquals(
                "type:sample.Plugin",
                relation.dstSymbol()
        );
        assertNull(relation.dstRawRef());
        assertEquals("ast", relation.origin());
        assertEquals("derived", relation.derivation());
        assertEquals(
                "exact_type_symbol",
                relation.attrs()
                        .get("match_strategy")
        );
        assertEquals(
                "ReflectionObservationResolver",
                relation.attrs()
                        .get("resolver")
        );
        assertEquals(
                "reflection_reference",
                relation.attrs()
                        .get("semantic_kind")
        );
        assertEquals(
                List.of("reflection-call"),
                relation.evidenceIds()
        );
    }

    @Test
    @DisplayName("parameter type이 포함된 내부 method symbol을 정확히 선택한다")
    void resolvesExactMethodSignature() {
        NormalizedSymbolFact stringMethod =
                symbol(
                        "method:sample.Plugin#initialize(java.lang.String)",
                        "initialize",
                        "method",
                        null,
                        "type:sample.Plugin"
                );

        NormalizedSymbolFact integerMethod =
                symbol(
                        "method:sample.Plugin#initialize(java.lang.Integer)",
                        "initialize",
                        "method",
                        null,
                        "type:sample.Plugin"
                );

        ObjectNode attrs =
                JsonNodeFactory.instance
                        .objectNode();

        attrs.put(
                "reflection_kind",
                "method"
        );
        attrs.put(
                "target_type",
                "sample.Plugin"
        );
        attrs.put(
                "member_name",
                "initialize"
        );
        attrs.putArray(
                "parameter_types"
        ).add(
                "java.lang.String"
        );

        NormalizedRelationFact relation =
                generate(
                        List.of(
                                stringMethod,
                                integerMethod
                        ),
                        List.of(observation(
                                "method:sample.Loader#invoke()",
                                null,
                                null,
                                "ast",
                                attrs,
                                List.of("get-method")
                        ))
                ).candidates()
                        .get(0)
                        .relation();

        assertEquals(
                "reflects_method",
                relation.kind()
        );
        assertEquals(
                "method:sample.Plugin#initialize(java.lang.String)",
                relation.dstSymbol()
        );
        assertEquals(
                "exact_method_signature",
                relation.attrs()
                        .get("match_strategy")
        );
    }

    @Test
    @DisplayName("parameter 정보가 없는 overload는 ambiguous raw reference로 유지한다")
    void keepsAmbiguousMethodOverload() {
        List<NormalizedSymbolFact> symbols =
                List.of(
                        symbol(
                                "method:sample.Plugin#initialize(java.lang.String)",
                                "initialize",
                                "method",
                                null,
                                "type:sample.Plugin"
                        ),
                        symbol(
                                "method:sample.Plugin#initialize(java.lang.Integer)",
                                "initialize",
                                "method",
                                null,
                                "type:sample.Plugin"
                        )
                );

        ObjectNode attrs =
                JsonNodeFactory.instance
                        .objectNode();

        attrs.put(
                "api_method",
                "getDeclaredMethod"
        );
        attrs.put(
                "owner_type",
                "sample.Plugin"
        );
        attrs.put(
                "member_name",
                "initialize"
        );

        NormalizedRelationFact relation =
                generate(
                        symbols,
                        List.of(observation(
                                "method:sample.Loader#lookup()",
                                null,
                                null,
                                "observed",
                                attrs,
                                List.of()
                        ))
                ).candidates()
                        .get(0)
                        .relation();

        assertNull(relation.dstSymbol());
        assertEquals(
                "method:sample.Plugin#initialize()",
                relation.dstRawRef()
        );
        assertEquals(
                "ambiguous_method_overload",
                relation.attrs()
                        .get("match_strategy")
        );
        assertEquals(
                2,
                relation.attrs()
                        .get("candidate_count")
        );
        assertEquals(
                "Multiple reflected method candidates matched the extracted symbols",
                relation.resolutionReason()
        );
    }

    @Test
    @DisplayName("api_method만으로 field Reflection을 분류하고 symbol을 해석한다")
    void classifiesAndResolvesField() {
        NormalizedSymbolFact field =
                symbol(
                        "field:sample.Plugin#state",
                        "state",
                        "field",
                        null,
                        "type:sample.Plugin"
                );

        ObjectNode attrs =
                JsonNodeFactory.instance
                        .objectNode();

        attrs.put(
                "api_method",
                "getDeclaredField"
        );
        attrs.put(
                "class_name",
                "sample.Plugin"
        );
        attrs.put(
                "member_name",
                "state"
        );

        NormalizedRelationFact relation =
                generate(
                        List.of(field),
                        List.of(observation(
                                "method:sample.Loader#field()",
                                null,
                                null,
                                "bytecode",
                                attrs,
                                List.of("field-access")
                        ))
                ).candidates()
                        .get(0)
                        .relation();

        assertEquals(
                "reflects_field",
                relation.kind()
        );
        assertEquals(
                "field:sample.Plugin#state",
                relation.dstSymbol()
        );
        assertEquals(
                "field",
                relation.attrs()
                        .get("reflection_kind")
        );
        assertEquals(
                "exact_field_symbol",
                relation.attrs()
                        .get("match_strategy")
        );
        assertEquals(
                "bytecode",
                relation.origin()
        );
    }

    @Test
    @DisplayName("내부 constructor symbol이 없으면 정적 raw reference를 유지한다")
    void keepsStaticConstructorRawReference() {
        ObjectNode attrs =
                JsonNodeFactory.instance
                        .objectNode();

        attrs.put(
                "api_method",
                "getConstructor"
        );
        attrs.put(
                "target_type",
                "sample.Plugin"
        );
        attrs.putArray(
                "parameter_types"
        ).add(
                "java.lang.String"
        );

        NormalizedRelationFact relation =
                generate(
                        List.of(),
                        List.of(observation(
                                "method:sample.Loader#create()",
                                null,
                                null,
                                "ast",
                                attrs,
                                List.of("constructor-reflection")
                        ))
                ).candidates()
                        .get(0)
                        .relation();

        assertEquals(
                "reflects_constructor",
                relation.kind()
        );
        assertNull(relation.dstSymbol());
        assertEquals(
                "ctor:sample.Plugin(java.lang.String)",
                relation.dstRawRef()
        );
        assertEquals(
                "static_constructor_raw_ref",
                relation.attrs()
                        .get("match_strategy")
        );
    }

    @Test
    @DisplayName("분류할 수 없는 Reflection API는 explicit unknown raw reference로 남긴다")
    void keepsUnknownReflectionApi() {
        ObjectNode attrs =
                JsonNodeFactory.instance
                        .objectNode();

        attrs.put(
                "api_method",
                "setAccessible"
        );

        NormalizedRelationFact relation =
                generate(
                        List.of(),
                        List.of(observation(
                                "method:sample.Loader#unknown()",
                                null,
                                null,
                                "observed",
                                attrs,
                                List.of()
                        ))
                ).candidates()
                        .get(0)
                        .relation();

        assertEquals(
                "reflects_type",
                relation.kind()
        );
        assertEquals(
                "reflection:setAccessible",
                relation.dstRawRef()
        );
        assertEquals(
                "unknown",
                relation.attrs()
                        .get("reflection_kind")
        );
        assertEquals(
                "unknown_api",
                relation.attrs()
                        .get("match_strategy")
        );
    }

    @Test
    @DisplayName("target_type_ref source_text와 simple type match를 지원한다")
    void resolvesTargetFromTypeRefSourceText() {
        NormalizedSymbolFact pluginType =
                symbol(
                        "type:sample.Plugin",
                        "Plugin",
                        "type",
                        "sample.Plugin",
                        null
                );

        ObjectNode typeRef =
                JsonNodeFactory.instance
                        .objectNode();

        typeRef.put(
                "source_text",
                "Plugin.class"
        );

        ObjectNode attrs =
                JsonNodeFactory.instance
                        .objectNode();

        attrs.put(
                "reflection_kind",
                "type"
        );

        NormalizedRelationFact relation =
                generate(
                        List.of(pluginType),
                        List.of(observation(
                                "method:sample.Loader#load()",
                                null,
                                typeRef,
                                "ast",
                                attrs,
                                List.of()
                        ))
                ).candidates()
                        .get(0)
                        .relation();

        assertEquals(
                "type:sample.Plugin",
                relation.dstSymbol()
        );
    }

    @Test
    @DisplayName("siteSymbol이 없으면 후보 없이 warning을 남긴다")
    void warnsWhenSiteSymbolIsMissing() {
        ObjectNode attrs =
                JsonNodeFactory.instance
                        .objectNode();

        attrs.put(
                "reflection_kind",
                "type"
        );

        ObservationPromotionCandidateGenerationResult result =
                generate(
                        List.of(),
                        List.of(observation(
                                null,
                                "type:sample.Plugin",
                                null,
                                "ast",
                                attrs,
                                List.of()
                        ))
                );

        assertEquals(1, result.eligibleObservationCount());
        assertTrue(result.candidates().isEmpty());
        assertEquals(1, result.warnings().size());
    }

    @Test
    @DisplayName("다른 Observation 종류는 10-3-3C 후보 생성에서 제외한다")
    void ignoresOtherObservations() {
        NormalizedObservationFact event =
                new NormalizedObservationFact(
                        "event_publication",
                        "method:sample.Service#publish()",
                        "type:sample.Event",
                        null,
                        null,
                        "observed",
                        new BigDecimal("0.9"),
                        null,
                        List.of()
                );

        ObservationPromotionCandidateGenerationResult result =
                ReflectionShadowCandidateGenerator
                        .generate(
                                new NormalizedFactsDocument(
                                        "2",
                                        Map.of(),
                                        List.of(),
                                        List.of(),
                                        List.of(event)
                                ),
                                objectMapper
                        );

        assertEquals(0, result.eligibleObservationCount());
        assertTrue(result.candidates().isEmpty());
    }

    private ObservationPromotionCandidateGenerationResult generate(
            List<NormalizedSymbolFact> symbols,
            List<NormalizedObservationFact> observations
    ) {
        return ReflectionShadowCandidateGenerator
                .generate(
                        new NormalizedFactsDocument(
                                "2",
                                Map.of(),
                                symbols,
                                List.of(),
                                observations
                        ),
                        objectMapper
                );
    }

    private NormalizedObservationFact observation(
            String siteSymbol,
            String targetSymbol,
            JsonNode targetTypeRef,
            String origin,
            JsonNode attrs,
            List<String> evidenceIds
    ) {
        return new NormalizedObservationFact(
                "reflection_site",
                siteSymbol,
                targetSymbol,
                targetTypeRef,
                null,
                origin,
                new BigDecimal("0.9"),
                attrs,
                evidenceIds
        );
    }

    private NormalizedSymbolFact symbol(
            String symbol,
            String name,
            String kind,
            String qualifiedName,
            String ownerTypeSymbol
    ) {
        ObjectNode node =
                JsonNodeFactory.instance
                        .objectNode();

        node.put("symbol", symbol);
        node.put("name", name);
        node.put("kind", kind);

        if (qualifiedName != null) {
            node.put(
                    "qualifiedName",
                    qualifiedName
            );
        }

        if (ownerTypeSymbol != null) {
            node.put(
                    "ownerTypeSymbol",
                    ownerTypeSymbol
            );
        }

        return objectMapper.convertValue(
                node,
                NormalizedSymbolFact.class
        );
    }
}
