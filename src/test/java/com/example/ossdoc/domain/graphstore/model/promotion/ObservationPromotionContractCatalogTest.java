package com.example.ossdoc.domain.graphstore.model.promotion;

import com.example.ossdoc.domain.extraction.enums.ObservationKind;
import com.example.ossdoc.domain.extraction.service.support.resolve.BeanObservationResolver;
import com.example.ossdoc.domain.extraction.service.support.resolve.ConfigurationObservationResolver;
import com.example.ossdoc.domain.extraction.service.support.resolve.DiObservationResolver;
import com.example.ossdoc.domain.extraction.service.support.resolve.EndpointObservationResolver;
import com.example.ossdoc.domain.extraction.service.support.resolve.EventObservationResolver;
import com.example.ossdoc.domain.extraction.service.support.resolve.ObservationRelationResolver;
import com.example.ossdoc.domain.extraction.service.support.resolve.ReflectionObservationResolver;
import com.example.ossdoc.domain.extraction.service.support.resolve.SpiObservationResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObservationPromotionContractCatalogTest {

    private final List<ObservationRelationResolver> resolvers =
            List.of(
                    new EndpointObservationResolver(),
                    new BeanObservationResolver(),
                    new ConfigurationObservationResolver(),
                    new DiObservationResolver(),
                    new EventObservationResolver(),
                    new SpiObservationResolver(),
                    new ReflectionObservationResolver()
            );

    @Test
    @DisplayName("Extraction resolver의 supportedKinds와 GraphStore 승격 계약이 정확히 일치한다")
    void catalogMatchesResolverSupportedKinds() {
        Map<String, String> resolverByKind =
                resolvers.stream()
                        .flatMap(resolver ->
                                resolver.supportedKinds()
                                        .stream()
                                        .map(kind ->
                                                Map.entry(
                                                        kind.code(),
                                                        resolver.getClass()
                                                                .getSimpleName()
                                                )
                                        )
                        )
                        .collect(Collectors.toMap(
                                Map.Entry::getKey,
                                Map.Entry::getValue
                        ));

        assertEquals(
                resolverByKind.keySet(),
                ObservationPromotionContractCatalog
                        .promotableObservationKinds()
        );

        for (Map.Entry<String, String> entry
                : resolverByKind.entrySet()) {
            ObservationPromotionContract contract =
                    ObservationPromotionContractCatalog
                            .require(entry.getKey());

            assertEquals(
                    entry.getValue(),
                    contract.resolverClassName(),
                    entry.getKey()
            );
        }
    }

    @Test
    @DisplayName("승격 계약은 resolver의 relationKind와 semanticKind를 고정한다")
    void catalogFixesRelationAndSemanticMappings() {
        assertContract(
                "di_injection_site",
                Set.of("injects"),
                Set.of("dependency_injection")
        );

        assertContract(
                "di_provider",
                Set.of("declares_bean"),
                Set.of("bean_declaration")
        );

        assertContract(
                "config_wiring",
                Set.of("configures_bean"),
                Set.of("configuration_wiring")
        );

        assertContract(
                "http_endpoint",
                Set.of("handles_endpoint"),
                Set.of("http_endpoint")
        );

        assertContract(
                "event_publication",
                Set.of("publishes_event"),
                Set.of("event_publication")
        );

        assertContract(
                "event_subscription",
                Set.of("listens_event"),
                Set.of("event_subscription")
        );

        assertContract(
                "module_uses",
                Set.of("loads_service"),
                Set.of("spi_service_load")
        );

        assertContract(
                "module_provides",
                Set.of("provides_spi"),
                Set.of("spi_provider")
        );

        assertContract(
                "spi_provider",
                Set.of(
                        "provides_spi",
                        "loads_service"
                ),
                Set.of(
                        "spi_provider",
                        "spi_service_load"
                )
        );

        assertContract(
                "reflection_site",
                Set.of(
                        "reflects_type",
                        "reflects_method",
                        "reflects_field",
                        "reflects_constructor"
                ),
                Set.of("reflection_reference")
        );
    }

    @Test
    @DisplayName("모든 승격 관계는 derived와 공통 정책 attrs를 요구한다")
    void allContractsRequireDerivedPolicyMetadata() {
        Set<String> requiredAttrs =
                Set.of(
                        "semantic_kind",
                        "resolver",
                        "resolution_basis",
                        "confidence_band",
                        "default_visible"
                );

        for (ObservationPromotionContract contract
                : ObservationPromotionContractCatalog.all()) {
            assertEquals(
                    "derived",
                    contract.derivation()
            );

            assertEquals(
                    requiredAttrs,
                    contract.requiredRelationAttrs()
            );
        }

        assertEquals(
                ObservationEvidencePolicy
                        .SOURCE_AND_MATCHED_OBSERVATIONS,
                ObservationPromotionContractCatalog
                        .require("di_injection_site")
                        .evidencePolicy()
        );
    }

    @Test
    @DisplayName("resolver가 없는 Observation은 명시적으로 승격 대상에서 제외된다")
    void unsupportedObservationKindsStayOutOfCatalog() {
        Set<ObservationKind> unsupported =
                Set.of(
                        ObservationKind.SCHEDULED_TASK,
                        ObservationKind.ASYNC_METHOD,
                        ObservationKind.README_MENTION,
                        ObservationKind.MODULE_EXPORTS
                );

        for (ObservationKind kind : unsupported) {
            assertFalse(
                    ObservationPromotionContractCatalog
                            .isPromotable(kind.code()),
                    kind.code()
            );
        }

        assertTrue(
                ObservationPromotionContractCatalog
                        .isPromotable(
                                " EVENT_PUBLICATION "
                        )
        );
    }

    private void assertContract(
            String observationKind,
            Set<String> relationKinds,
            Set<String> semanticKinds
    ) {
        ObservationPromotionContract contract =
                ObservationPromotionContractCatalog
                        .require(observationKind);

        assertEquals(
                relationKinds,
                contract.relationKinds()
        );

        assertEquals(
                semanticKinds,
                contract.semanticKinds()
        );
    }
}
