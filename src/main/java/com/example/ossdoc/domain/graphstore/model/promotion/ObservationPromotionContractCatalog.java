package com.example.ossdoc.domain.graphstore.model.promotion;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Observation → semantic Relation 승격 계약의 단일 카탈로그.
 *
 * 이번 단계에서는 계약만 고정한다.
 * 실제 GraphStore shadow 승격은 다음 단계에서 이 카탈로그를 사용한다.
 */
public final class ObservationPromotionContractCatalog {

    private static final String DERIVED = "derived";

    private static final Set<String> COMMON_REQUIRED_ATTRS =
            Set.of(
                    "semantic_kind",
                    "resolver",
                    "resolution_basis",
                    "confidence_band",
                    "default_visible"
            );

    private static final Map<String, ObservationPromotionContract>
            CONTRACTS = buildContracts();

    private ObservationPromotionContractCatalog() {
    }

    public static Optional<ObservationPromotionContract> find(
            String observationKind
    ) {
        String normalized = normalize(
                observationKind
        );

        if (normalized == null) {
            return Optional.empty();
        }

        return Optional.ofNullable(
                CONTRACTS.get(normalized)
        );
    }

    public static ObservationPromotionContract require(
            String observationKind
    ) {
        return find(observationKind)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Unsupported observation promotion kind: "
                                        + observationKind
                        )
                );
    }

    public static List<ObservationPromotionContract> all() {
        return List.copyOf(
                CONTRACTS.values()
        );
    }

    public static Set<String> promotableObservationKinds() {
        return CONTRACTS.keySet();
    }

    public static boolean isPromotable(
            String observationKind
    ) {
        return find(observationKind).isPresent();
    }

    private static Map<String, ObservationPromotionContract>
    buildContracts() {
        LinkedHashMap<String, ObservationPromotionContract>
                contracts = new LinkedHashMap<>();

        register(
                contracts,
                contract(
                        "di_injection_site",
                        Set.of("injects"),
                        Set.of("dependency_injection"),
                        "DiObservationResolver",
                        ObservationEvidencePolicy
                                .SOURCE_AND_MATCHED_OBSERVATIONS,
                        false
                )
        );

        register(
                contracts,
                contract(
                        "di_provider",
                        Set.of("declares_bean"),
                        Set.of("bean_declaration"),
                        "BeanObservationResolver",
                        ObservationEvidencePolicy
                                .SOURCE_OBSERVATION,
                        false
                )
        );

        register(
                contracts,
                contract(
                        "config_wiring",
                        Set.of("configures_bean"),
                        Set.of("configuration_wiring"),
                        "ConfigurationObservationResolver",
                        ObservationEvidencePolicy
                                .SOURCE_OBSERVATION,
                        false
                )
        );

        register(
                contracts,
                contract(
                        "http_endpoint",
                        Set.of("handles_endpoint"),
                        Set.of("http_endpoint"),
                        "EndpointObservationResolver",
                        ObservationEvidencePolicy
                                .SOURCE_OBSERVATION,
                        false
                )
        );

        register(
                contracts,
                contract(
                        "event_publication",
                        Set.of("publishes_event"),
                        Set.of("event_publication"),
                        "EventObservationResolver",
                        ObservationEvidencePolicy
                                .SOURCE_OBSERVATION,
                        false
                )
        );

        register(
                contracts,
                contract(
                        "event_subscription",
                        Set.of("listens_event"),
                        Set.of("event_subscription"),
                        "EventObservationResolver",
                        ObservationEvidencePolicy
                                .SOURCE_OBSERVATION,
                        false
                )
        );

        /*
         * SPI_PROVIDER는 attrs에 implementation 정보가 있으면
         * PROVIDES_SPI, 없으면 ServiceLoader 사용으로 보고 LOADS_SERVICE가 된다.
         */
        register(
                contracts,
                contract(
                        "spi_provider",
                        Set.of(
                                "provides_spi",
                                "loads_service"
                        ),
                        Set.of(
                                "spi_provider",
                                "spi_service_load"
                        ),
                        "SpiObservationResolver",
                        ObservationEvidencePolicy
                                .SOURCE_OBSERVATION,
                        true
                )
        );

        register(
                contracts,
                contract(
                        "module_uses",
                        Set.of("loads_service"),
                        Set.of("spi_service_load"),
                        "SpiObservationResolver",
                        ObservationEvidencePolicy
                                .SOURCE_OBSERVATION,
                        false
                )
        );

        register(
                contracts,
                contract(
                        "module_provides",
                        Set.of("provides_spi"),
                        Set.of("spi_provider"),
                        "SpiObservationResolver",
                        ObservationEvidencePolicy
                                .SOURCE_OBSERVATION,
                        false
                )
        );

        /*
         * reflection_kind 및 대상 member 정보에 따라
         * 네 relation kind 중 하나를 선택한다.
         */
        register(
                contracts,
                contract(
                        "reflection_site",
                        Set.of(
                                "reflects_type",
                                "reflects_method",
                                "reflects_field",
                                "reflects_constructor"
                        ),
                        Set.of("reflection_reference"),
                        "ReflectionObservationResolver",
                        ObservationEvidencePolicy
                                .SOURCE_OBSERVATION,
                        true
                )
        );

        return Collections.unmodifiableMap(
                contracts
        );
    }

    private static ObservationPromotionContract contract(
            String observationKind,
            Set<String> relationKinds,
            Set<String> semanticKinds,
            String resolverClassName,
            ObservationEvidencePolicy evidencePolicy,
            boolean relationKindSelectedDynamically
    ) {
        return new ObservationPromotionContract(
                observationKind,
                relationKinds,
                semanticKinds,
                resolverClassName,
                DERIVED,
                evidencePolicy,
                relationKindSelectedDynamically,
                COMMON_REQUIRED_ATTRS
        );
    }

    private static void register(
            Map<String, ObservationPromotionContract> target,
            ObservationPromotionContract contract
    ) {
        ObservationPromotionContract previous =
                target.put(
                        contract.observationKind(),
                        contract
                );

        if (previous != null) {
            throw new IllegalStateException(
                    "Duplicate observation promotion contract: "
                            + contract.observationKind()
            );
        }
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }

        String normalized =
                value.trim().toLowerCase();

        return normalized.isEmpty()
                ? null
                : normalized;
    }
}
