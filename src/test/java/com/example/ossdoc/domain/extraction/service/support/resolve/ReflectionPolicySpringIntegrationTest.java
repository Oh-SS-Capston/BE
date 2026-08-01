package com.example.ossdoc.domain.extraction.service.support.resolve;

import com.example.ossdoc.domain.extraction.dto.model.ExtractionAggregate;
import com.example.ossdoc.domain.extraction.dto.model.ObservationFact;
import com.example.ossdoc.domain.extraction.dto.model.ObservationTable;
import com.example.ossdoc.domain.extraction.dto.model.SymbolFact;
import com.example.ossdoc.domain.extraction.dto.model.SymbolTable;
import com.example.ossdoc.domain.extraction.enums.FactOriginKind;
import com.example.ossdoc.domain.extraction.enums.ObservationKind;
import com.example.ossdoc.domain.extraction.enums.ResolutionStatus;
import com.example.ossdoc.domain.extraction.enums.SymbolKind;
import com.example.ossdoc.domain.extraction.service.support.policy.RelationConfidencePolicy;
import com.example.ossdoc.domain.extraction.service.support.policy.RelationResolutionPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(SpringExtension.class)
@Import({
        ObservationRelationResolutionService.class,
        ReflectionObservationResolver.class,
        RelationResolutionPolicy.class,
        RelationConfidencePolicy.class
})
class ReflectionPolicySpringIntegrationTest {

    @Autowired
    private ObservationRelationResolutionService resolutionService;

    @Autowired
    private RelationResolutionPolicy resolutionPolicy;

    @Autowired
    private RelationConfidencePolicy confidencePolicy;

    @Test
    @DisplayName("Spring이 Reflection Resolver에 공통 정책을 주입해 관계 메타데이터를 생성한다")
    void injectsSharedPoliciesIntoReflectionResolver() {
        assertNotNull(resolutionPolicy);
        assertNotNull(confidencePolicy);
        assertEquals(
                List.of(ReflectionObservationResolver.class),
                resolutionService.resolvers().stream()
                        .map(Object::getClass)
                        .toList()
        );

        ObservationFact reflection = ObservationFact.builder()
                .kind(ObservationKind.REFLECTION_SITE)
                .siteSymbol("method:sample.Client#load()")
                .evidenceIds(List.of("ev-ast", "ev-bytecode"))
                .origin(FactOriginKind.AST_AND_BYTECODE)
                .confidenceHint(0.9)
                .attrs(Map.of(
                        "api_method", "forName",
                        "reflection_kind", "type",
                        "target_type", "sample.Target"
                ))
                .build();

        SymbolFact target = SymbolFact.builder()
                .symbol("type:sample.Target")
                .kind(SymbolKind.TYPE)
                .name("Target")
                .qualifiedName("sample.Target")
                .build();

        ObservationResolutionResult result = resolutionService.resolve(
                ExtractionAggregate.builder()
                        .symbols(SymbolTable.builder()
                                .types(List.of(target))
                                .constructors(List.of())
                                .methods(List.of())
                                .fields(List.of())
                                .build())
                        .observations(ObservationTable.builder()
                                .reflectionSites(List.of(reflection))
                                .build())
                        .build()
        );

        assertTrue(result.warnings().isEmpty());
        assertEquals(1, result.relations().size());

        var relation = result.relations().get(0);
        assertEquals(
                ResolutionStatus.RESOLVED,
                relation.resolution().status()
        );
        assertEquals(0.975, relation.confidenceHint(), 0.0001);
        assertEquals(
                "exact_symbol",
                relation.attrs().get("resolution_basis")
        );
        assertEquals("high", relation.attrs().get("confidence_band"));
        assertEquals(true, relation.attrs().get("default_visible"));
    }
}
