package com.example.ossdoc.domain.extraction.service.support.resolve;

import com.example.ossdoc.domain.extraction.dto.model.ExtractionAggregate;
import com.example.ossdoc.domain.extraction.dto.model.ObservationFact;
import com.example.ossdoc.domain.extraction.dto.model.ObservationTable;
import com.example.ossdoc.domain.extraction.dto.model.RelationFact;
import com.example.ossdoc.domain.extraction.dto.model.SymbolFact;
import com.example.ossdoc.domain.extraction.dto.model.SymbolTable;
import com.example.ossdoc.domain.extraction.dto.model.TypeRef;
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

@ExtendWith(SpringExtension.class)
@Import({
        ObservationRelationResolutionService.class,
        RelationResolutionPolicy.class,
        RelationConfidencePolicy.class,
        DiObservationResolver.class
})
class DiPolicySpringIntegrationTest {

    @Autowired
    private ObservationRelationResolutionService resolutionService;

    @Test
    @DisplayName("Spring 공통 정책 Bean으로 DI 관계의 resolution과 confidence 메타데이터를 계산한다")
    void appliesSharedPoliciesToDiRelation() {
        String fieldSymbol = "field:sample.UserController#userService";
        String providerSymbol = "type:sample.UserServiceImpl";

        ObservationFact injection = ObservationFact.builder()
                .kind(ObservationKind.DI_INJECTION_SITE)
                .siteSymbol(fieldSymbol)
                .targetTypeRef(typeRef("sample.UserService"))
                .evidenceIds(List.of("ev-injection"))
                .origin(FactOriginKind.AST)
                .confidenceHint(0.9)
                .build();

        ObservationFact provider = ObservationFact.builder()
                .kind(ObservationKind.DI_PROVIDER)
                .siteSymbol(providerSymbol)
                .targetTypeRef(typeRef("sample.UserServiceImpl"))
                .evidenceIds(List.of("ev-provider"))
                .origin(FactOriginKind.BYTECODE)
                .confidenceHint(0.9)
                .attrs(Map.of(
                        "provided_type", "sample.UserServiceImpl",
                        "bean_names", List.of("userServiceImpl"),
                        "qualifiers", List.of(),
                        "primary", false,
                        "provider_kind", "service_type"
                ))
                .build();

        SymbolFact field = SymbolFact.builder()
                .symbol(fieldSymbol)
                .kind(SymbolKind.FIELD)
                .ownerSymbol("type:sample.UserController")
                .build();

        SymbolFact providerType = SymbolFact.builder()
                .symbol(providerSymbol)
                .kind(SymbolKind.TYPE)
                .qualifiedName("sample.UserServiceImpl")
                .interfaceTypeRefs(List.of(typeRef("sample.UserService")))
                .build();

        ExtractionAggregate aggregate = ExtractionAggregate.builder()
                .symbols(SymbolTable.builder()
                        .types(List.of(providerType))
                        .fields(List.of(field))
                        .constructors(List.of())
                        .methods(List.of())
                        .build())
                .observations(ObservationTable.builder()
                        .diInjectionSites(List.of(injection))
                        .diProviders(List.of(provider))
                        .build())
                .build();

        ObservationResolutionResult result = resolutionService.resolve(aggregate);

        assertEquals(1, result.relations().size());
        RelationFact relation = result.relations().get(0);
        assertEquals(ResolutionStatus.RESOLVED, relation.resolution().status());
        assertEquals(0.975, relation.confidenceHint(), 0.0001);
        assertEquals("exact_symbol", relation.attrs().get("resolution_basis"));
        assertEquals("high", relation.attrs().get("confidence_band"));
        assertEquals(true, relation.attrs().get("default_visible"));
    }

    private TypeRef typeRef(String raw) {
        return TypeRef.builder()
                .raw(raw)
                .arrayDim(0)
                .primitive(false)
                .unresolved(false)
                .build();
    }
}
