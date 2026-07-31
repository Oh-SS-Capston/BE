package com.example.ossdoc.domain.extraction.service.support.resolve;

import com.example.ossdoc.domain.extraction.dto.model.ExtractionAggregate;
import com.example.ossdoc.domain.extraction.dto.model.ObservationFact;
import com.example.ossdoc.domain.extraction.dto.model.ObservationTable;
import com.example.ossdoc.domain.extraction.dto.model.RelationFact;
import com.example.ossdoc.domain.extraction.dto.model.TypeRef;
import com.example.ossdoc.domain.extraction.enums.DerivationKind;
import com.example.ossdoc.domain.extraction.enums.FactOriginKind;
import com.example.ossdoc.domain.extraction.enums.ObservationKind;
import com.example.ossdoc.domain.extraction.enums.RelationKind;
import com.example.ossdoc.domain.extraction.enums.ResolutionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BeanObservationResolverTest {

    private final BeanObservationResolver resolver =
            new BeanObservationResolver();

    @Test
    @DisplayName("복수 Bean 이름을 이름별 DECLARES_BEAN 관계로 생성한다")
    void resolvesDeclaredBeanNames() {
        ObservationFact observation = ObservationFact.builder()
                .kind(ObservationKind.DI_PROVIDER)
                .siteSymbol("method:sample.AppConfig#objectMapper()")
                .targetTypeRef(typeRef("sample.ObjectMapper"))
                .evidenceIds(List.of("ev-ast", "ev-bytecode"))
                .origin(FactOriginKind.AST_AND_BYTECODE)
                .confidenceHint(0.9)
                .attrs(Map.of(
                        "provider_kind", "bean_method",
                        "bean_names", List.of(
                                "apiMapper",
                                "objectMapper"
                        ),
                        "provided_type", "sample.ObjectMapper",
                        "primary", true,
                        "qualifiers", List.of("api"),
                        "owner_config_symbol", "type:sample.AppConfig"
                ))
                .build();

        ObservationResolutionResult result = resolver.resolve(
                contextOf(observation)
        );

        assertTrue(result.warnings().isEmpty());
        assertEquals(2, result.relations().size());

        Set<String> destinations = result.relations().stream()
                .map(RelationFact::dstRawRef)
                .collect(Collectors.toSet());

        assertEquals(
                Set.of(
                        "bean:apiMapper",
                        "bean:objectMapper"
                ),
                destinations
        );

        for (RelationFact relation : result.relations()) {
            assertEquals(RelationKind.DECLARES_BEAN, relation.kind());
            assertEquals(
                    "method:sample.AppConfig#objectMapper()",
                    relation.srcSymbol()
            );
            assertEquals(
                    ResolutionStatus.RESOLVED,
                    relation.resolution().status()
            );
            assertEquals(DerivationKind.DERIVED, relation.derivation());
            assertEquals(
                    FactOriginKind.AST_AND_BYTECODE,
                    relation.origin()
            );
            assertEquals(
                    List.of("ev-ast", "ev-bytecode"),
                    relation.evidenceIds()
            );
            assertEquals(
                    "sample.ObjectMapper",
                    relation.attrs().get("provided_type")
            );
            assertEquals(true, relation.attrs().get("primary"));
            assertEquals(
                    List.of("api"),
                    relation.attrs().get("qualifiers")
            );
            assertEquals(
                    "BeanObservationResolver",
                    relation.attrs().get("resolver")
            );
            assertEquals(
                    "declared",
                    relation.attrs().get("name_resolution")
            );
        }
    }

    @Test
    @DisplayName("Bean 이름이 없으면 제공 타입에서 이름을 추론해 PARTIAL 관계로 보존한다")
    void infersBeanNameFromProvidedType() {
        ObservationFact observation = ObservationFact.builder()
                .kind(ObservationKind.DI_PROVIDER)
                .siteSymbol("type:sample.UserService")
                .targetTypeRef(typeRef("sample.UserService"))
                .evidenceIds(List.of("ev-service"))
                .origin(FactOriginKind.AST)
                .confidenceHint(0.9)
                .attrs(Map.of(
                        "provider_kind", "service_type",
                        "bean_names", List.of()
                ))
                .build();

        ObservationResolutionResult result = resolver.resolve(
                contextOf(observation)
        );

        assertEquals(1, result.relations().size());

        RelationFact relation = result.relations().get(0);
        assertEquals("bean:userService", relation.dstRawRef());
        assertEquals(
                ResolutionStatus.PARTIAL,
                relation.resolution().status()
        );
        assertEquals(
                "Bean name inferred from provided type",
                relation.resolution().reason()
        );
        assertEquals(0.7, relation.confidenceHint());
        assertEquals(
                "inferred",
                relation.attrs().get("name_resolution")
        );
    }

    @Test
    @DisplayName("siteSymbol이 없는 DI_PROVIDER는 경고 후 건너뛴다")
    void skipsProviderWithoutSiteSymbol() {
        ObservationFact observation = ObservationFact.builder()
                .kind(ObservationKind.DI_PROVIDER)
                .targetTypeRef(typeRef("sample.UserService"))
                .attrs(Map.of(
                        "bean_names", List.of("userService")
                ))
                .build();

        ObservationResolutionResult result = resolver.resolve(
                contextOf(observation)
        );

        assertTrue(result.relations().isEmpty());
        assertFalse(result.warnings().isEmpty());
        assertTrue(
                result.warnings().get(0).contains("siteSymbol")
        );
    }

    private TypeRef typeRef(String raw) {
        return TypeRef.builder()
                .raw(raw)
                .arrayDim(0)
                .primitive(false)
                .unresolved(false)
                .build();
    }

    private ObservationResolutionContext contextOf(
            ObservationFact... observations
    ) {
        ExtractionAggregate aggregate = ExtractionAggregate.builder()
                .observations(ObservationTable.builder()
                        .diProviders(List.of(observations))
                        .build())
                .build();

        return ObservationResolutionContext.from(aggregate);
    }
}
