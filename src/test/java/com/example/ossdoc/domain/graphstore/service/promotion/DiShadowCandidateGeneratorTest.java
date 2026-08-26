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

class DiShadowCandidateGeneratorTest {

    private final ObjectMapper objectMapper =
            new ObjectMapper().findAndRegisterModules();

    @Test
    @DisplayName("단일 exact component provider를 타입 INJECTS 후보로 연결하고 Evidence·origin을 병합한다")
    void resolvesSingleExactComponentProvider() {
        NormalizedObservationFact injection = injection(
                "field:sample.Controller#service",
                "sample.Service",
                false,
                "ast",
                null,
                List.of("inject-annotation", "inject-field")
        );

        ObjectNode providerAttrs = JsonNodeFactory.instance.objectNode();
        providerAttrs.put("provided_type", "sample.Service");

        NormalizedObservationFact provider = provider(
                "type:sample.ServiceImpl",
                "sample.Service",
                "bytecode",
                providerAttrs,
                List.of("provider-annotation")
        );

        NormalizedSymbolFact field = symbol(
                "field:sample.Controller#service",
                "service",
                "field",
                null,
                "type:sample.Controller",
                null,
                List.of()
        );

        NormalizedRelationFact relation = generate(
                List.of(field),
                List.of(injection, provider)
        ).candidates().get(0).relation();

        assertEquals("injects", relation.kind());
        assertEquals("type:sample.Controller", relation.srcSymbol());
        assertEquals("type:sample.ServiceImpl", relation.dstSymbol());
        assertNull(relation.dstRawRef());
        assertEquals("ast_and_bytecode", relation.origin());
        assertEquals("exact_type", relation.attrs().get("match_strategy"));
        assertEquals(
                "dependency_injection",
                relation.attrs().get("semantic_kind")
        );
        assertEquals(
                "DiObservationResolver",
                relation.attrs().get("resolver")
        );
        assertEquals(
                List.of(
                        "inject-annotation",
                        "inject-field",
                        "provider-annotation"
                ),
                relation.evidenceIds()
        );
    }

    @Test
    @DisplayName("qualifier는 여러 provider 중 하나를 선택하고 provider method를 bean raw reference로 연결한다")
    void qualifierSelectsProvider() {
        ObjectNode injectionAttrs = JsonNodeFactory.instance.objectNode();
        injectionAttrs.putArray("qualifiers").add("fastService");

        ObjectNode fast = providerAttrs(
                "sample.Service",
                "fastService",
                false
        );
        ObjectNode slow = providerAttrs(
                "sample.Service",
                "slowService",
                false
        );

        NormalizedRelationFact relation = generate(
                List.of(),
                List.of(
                        injection(
                                "ctor:sample.Controller#<init>(sample.Service)",
                                "sample.Service",
                                false,
                                "ast",
                                injectionAttrs,
                                List.of("inject")
                        ),
                        provider(
                                "method:sample.Config#fastService()",
                                "sample.Service",
                                "ast",
                                fast,
                                List.of("fast")
                        ),
                        provider(
                                "method:sample.Config#slowService()",
                                "sample.Service",
                                "ast",
                                slow,
                                List.of("slow")
                        )
                )
        ).candidates().get(0).relation();

        assertNull(relation.dstSymbol());
        assertEquals("bean:fastService", relation.dstRawRef());
        assertEquals("qualifier", relation.attrs().get("match_strategy"));
        assertEquals(2, relation.attrs().get("candidate_count"));
        assertEquals(List.of("inject", "fast"), relation.evidenceIds());
    }

    @Test
    @DisplayName("parameter name은 partial 선택을 수행하고 @Primary는 남은 다중 후보를 확정한다")
    void parameterNameAndPrimarySelection() {
        ObjectNode parameterAttrs = JsonNodeFactory.instance.objectNode();
        parameterAttrs.put("parameter", "secondaryService");

        NormalizedRelationFact byParameter = generate(
                List.of(),
                List.of(
                        injection(
                                "ctor:sample.Controller#<init>(sample.Service)",
                                "sample.Service",
                                false,
                                "ast",
                                parameterAttrs,
                                List.of("inject")
                        ),
                        provider(
                                "method:sample.Config#primaryService()",
                                "sample.Service",
                                "ast",
                                providerAttrs(
                                        "sample.Service",
                                        "primaryService",
                                        false
                                ),
                                List.of("primary")
                        ),
                        provider(
                                "method:sample.Config#secondaryService()",
                                "sample.Service",
                                "ast",
                                providerAttrs(
                                        "sample.Service",
                                        "secondaryService",
                                        false
                                ),
                                List.of("secondary")
                        )
                )
        ).candidates().get(0).relation();

        assertEquals("bean:secondaryService", byParameter.dstRawRef());
        assertEquals(
                "parameter_name",
                byParameter.attrs().get("match_strategy")
        );
        assertEquals(
                "Provider selected by injection parameter name",
                byParameter.resolutionReason()
        );

        NormalizedRelationFact byPrimary = generate(
                List.of(),
                List.of(
                        injection(
                                "field:sample.Controller#service",
                                "sample.Service",
                                false,
                                "ast",
                                null,
                                List.of("inject")
                        ),
                        provider(
                                "method:sample.Config#primaryService()",
                                "sample.Service",
                                "ast",
                                providerAttrs(
                                        "sample.Service",
                                        "primaryService",
                                        true
                                ),
                                List.of("primary")
                        ),
                        provider(
                                "method:sample.Config#secondaryService()",
                                "sample.Service",
                                "ast",
                                providerAttrs(
                                        "sample.Service",
                                        "secondaryService",
                                        false
                                ),
                                List.of("secondary")
                        )
                )
        ).candidates().get(0).relation();

        assertEquals("bean:primaryService", byPrimary.dstRawRef());
        assertEquals("primary", byPrimary.attrs().get("match_strategy"));
        assertEquals(true, byPrimary.attrs().get("primary"));
    }

    @Test
    @DisplayName("ambiguous와 qualifier mismatch는 임의 provider를 선택하지 않고 모든 후보 Evidence를 보존한다")
    void ambiguousAndQualifierMismatchStayRaw() {
        ObjectNode one = providerAttrs(
                "sample.Service",
                "one",
                false
        );
        ObjectNode two = providerAttrs(
                "sample.Service",
                "two",
                false
        );

        NormalizedRelationFact ambiguous = generate(
                List.of(),
                List.of(
                        injection(
                                "field:sample.Controller#service",
                                "sample.Service",
                                false,
                                "ast",
                                null,
                                List.of("inject")
                        ),
                        provider(
                                "method:sample.Config#one()",
                                "sample.Service",
                                "ast",
                                one,
                                List.of("one-provider")
                        ),
                        provider(
                                "method:sample.Config#two()",
                                "sample.Service",
                                "bytecode",
                                two,
                                List.of("two-provider")
                        )
                )
        ).candidates().get(0).relation();

        assertNull(ambiguous.dstSymbol());
        assertEquals("type:sample.Service", ambiguous.dstRawRef());
        assertEquals("ambiguous", ambiguous.attrs().get("match_strategy"));
        assertEquals(
                List.of("inject", "one-provider", "two-provider"),
                ambiguous.evidenceIds()
        );
        assertEquals("ast_and_bytecode", ambiguous.origin());

        ObjectNode qualifier = JsonNodeFactory.instance.objectNode();
        qualifier.put("qualifier", "missingService");

        NormalizedRelationFact mismatch = generate(
                List.of(),
                List.of(
                        injection(
                                "field:sample.Controller#service",
                                "sample.Service",
                                false,
                                "ast",
                                qualifier,
                                List.of("inject")
                        ),
                        provider(
                                "method:sample.Config#knownService()",
                                "sample.Service",
                                "ast",
                                providerAttrs(
                                        "sample.Service",
                                        "knownService",
                                        false
                                ),
                                List.of("provider")
                        )
                )
        ).candidates().get(0).relation();

        assertEquals(
                "qualifier_mismatch",
                mismatch.attrs().get("match_strategy")
        );
        assertEquals(
                "Injection qualifier did not match any DI provider candidate",
                mismatch.resolutionReason()
        );
    }

    @Test
    @DisplayName("unresolved simple type은 simple name으로 보정하고 provider interface도 exposed type으로 사용한다")
    void simpleNameAndInterfaceMatching() {
        NormalizedRelationFact simple = generate(
                List.of(),
                List.of(
                        injection(
                                "field:sample.Controller#service",
                                "Service",
                                true,
                                "ast",
                                null,
                                List.of("inject")
                        ),
                        provider(
                                "method:sample.Config#service()",
                                "sample.Service",
                                "ast",
                                providerAttrs(
                                        "sample.Service",
                                        "service",
                                        false
                                ),
                                List.of("provider")
                        )
                )
        ).candidates().get(0).relation();

        assertEquals("bean:service", simple.dstRawRef());
        assertEquals(
                "simple_type_name",
                simple.attrs().get("match_strategy")
        );

        NormalizedSymbolFact providerSymbol = symbol(
                "type:sample.ServiceImpl",
                "ServiceImpl",
                "type",
                "sample.ServiceImpl",
                null,
                null,
                List.of("sample.Service")
        );

        ObjectNode componentAttrs = JsonNodeFactory.instance.objectNode();
        componentAttrs.put("provided_type", "sample.ServiceImpl");

        NormalizedRelationFact byInterface = generate(
                List.of(providerSymbol),
                List.of(
                        injection(
                                "field:sample.Controller#service",
                                "sample.Service",
                                false,
                                "ast",
                                null,
                                List.of("inject")
                        ),
                        provider(
                                "type:sample.ServiceImpl",
                                "sample.ServiceImpl",
                                "ast",
                                componentAttrs,
                                List.of("provider")
                        )
                )
        ).candidates().get(0).relation();

        assertEquals("type:sample.ServiceImpl", byInterface.dstSymbol());
        assertEquals(
                "exact_type",
                byInterface.attrs().get("match_strategy")
        );
    }

    @Test
    @DisplayName("provider가 없으면 내부 타입 fallback 또는 unresolved raw reference를 생성한다")
    void fallbackRelations() {
        NormalizedSymbolFact internalType = symbol(
                "type:sample.Service",
                "Service",
                "type",
                "sample.Service",
                null,
                null,
                List.of()
        );

        NormalizedRelationFact internal = generate(
                List.of(internalType),
                List.of(injection(
                        "field:sample.Controller#service",
                        "sample.Service",
                        false,
                        "ast",
                        null,
                        List.of("inject")
                ))
        ).candidates().get(0).relation();

        assertEquals("type:sample.Service", internal.dstSymbol());
        assertEquals(
                "internal_type_fallback",
                internal.attrs().get("match_strategy")
        );

        NormalizedRelationFact unresolved = generate(
                List.of(),
                List.of(injection(
                        "field:sample.Controller#service",
                        "external.Service",
                        false,
                        "observed",
                        null,
                        List.of("inject")
                ))
        ).candidates().get(0).relation();

        assertEquals("type:external.Service", unresolved.dstRawRef());
        assertEquals(
                "unresolved_provider",
                unresolved.attrs().get("match_strategy")
        );
    }

    @Test
    @DisplayName("owner type을 해석할 수 없으면 warning을 남기며 DI_PROVIDER만으로는 후보를 만들지 않는다")
    void warningsAndProviderOnly() {
        ObservationPromotionCandidateGenerationResult invalid = generate(
                List.of(),
                List.of(injection(
                        "resource:injection",
                        "sample.Service",
                        false,
                        "ast",
                        null,
                        List.of()
                ))
        );

        assertEquals(1, invalid.eligibleObservationCount());
        assertTrue(invalid.candidates().isEmpty());
        assertEquals(1, invalid.warnings().size());

        ObservationPromotionCandidateGenerationResult providerOnly = generate(
                List.of(),
                List.of(provider(
                        "type:sample.Service",
                        "sample.Service",
                        "ast",
                        providerAttrs(
                                "sample.Service",
                                "service",
                                false
                        ),
                        List.of()
                ))
        );

        assertEquals(0, providerOnly.eligibleObservationCount());
        assertTrue(providerOnly.candidates().isEmpty());
    }

    private ObservationPromotionCandidateGenerationResult generate(
            List<NormalizedSymbolFact> symbols,
            List<NormalizedObservationFact> observations
    ) {
        return DiShadowCandidateGenerator.generate(
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

    private NormalizedObservationFact injection(
            String siteSymbol,
            String rawType,
            boolean unresolved,
            String origin,
            JsonNode attrs,
            List<String> evidenceIds
    ) {
        ObjectNode typeRef = JsonNodeFactory.instance.objectNode();
        typeRef.put("raw", rawType);
        typeRef.put("unresolved", unresolved);

        return new NormalizedObservationFact(
                "di_injection_site",
                siteSymbol,
                null,
                typeRef,
                "test injection",
                origin,
                new BigDecimal("0.9"),
                attrs,
                evidenceIds
        );
    }

    private NormalizedObservationFact provider(
            String siteSymbol,
            String rawType,
            String origin,
            JsonNode attrs,
            List<String> evidenceIds
    ) {
        ObjectNode typeRef = JsonNodeFactory.instance.objectNode();
        typeRef.put("raw", rawType);

        return new NormalizedObservationFact(
                "di_provider",
                siteSymbol,
                null,
                typeRef,
                null,
                origin,
                new BigDecimal("0.9"),
                attrs,
                evidenceIds
        );
    }

    private ObjectNode providerAttrs(
            String providedType,
            String beanName,
            boolean primary
    ) {
        ObjectNode attrs = JsonNodeFactory.instance.objectNode();
        attrs.put("provided_type", providedType);
        attrs.putArray("bean_names").add(beanName);
        attrs.put("primary", primary);
        return attrs;
    }

    private NormalizedSymbolFact symbol(
            String symbol,
            String name,
            String kind,
            String qualifiedName,
            String ownerTypeSymbol,
            String superclassTypeRef,
            List<String> interfaceTypeRefs
    ) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("symbol", symbol);
        node.put("name", name);
        node.put("kind", kind);

        if (qualifiedName != null) {
            node.put("qualifiedName", qualifiedName);
        }
        if (ownerTypeSymbol != null) {
            node.put("ownerTypeSymbol", ownerTypeSymbol);
        }
        if (superclassTypeRef != null) {
            node.put("superclassTypeRef", superclassTypeRef);
        }
        if (interfaceTypeRefs != null) {
            var values = node.putArray("interfaceTypeRefs");
            interfaceTypeRefs.forEach(values::add);
        }

        return objectMapper.convertValue(
                node,
                NormalizedSymbolFact.class
        );
    }
}
