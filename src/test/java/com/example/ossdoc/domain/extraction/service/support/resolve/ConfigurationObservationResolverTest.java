package com.example.ossdoc.domain.extraction.service.support.resolve;

import com.example.ossdoc.domain.extraction.dto.model.ExtractionAggregate;
import com.example.ossdoc.domain.extraction.dto.model.ObservationFact;
import com.example.ossdoc.domain.extraction.dto.model.ObservationTable;
import com.example.ossdoc.domain.extraction.dto.model.RelationFact;
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

class ConfigurationObservationResolverTest {

    private final ConfigurationObservationResolver resolver =
            new ConfigurationObservationResolver();

    @Test
    @DisplayName("Import 타입과 component scan 패키지를 대상별 CONFIGURES_BEAN 관계로 생성한다")
    void resolvesConfigurationWiringTargets() {
        ObservationFact observation = ObservationFact.builder()
                .kind(ObservationKind.CONFIG_WIRING)
                .siteSymbol("type:sample.AppConfig")
                .evidenceIds(List.of("ev-ast", "ev-bytecode"))
                .origin(FactOriginKind.AST_AND_BYTECODE)
                .confidenceHint(0.9)
                .attrs(Map.of(
                        "configuration_kind", "spring_configuration",
                        "configuration_kinds", List.of(
                                "spring_configuration",
                                "spring_import",
                                "spring_component_scan"
                        ),
                        "imported_types", List.of(
                                "sample.SecurityConfig",
                                "type:sample.AuditConfig"
                        ),
                        "component_scan_packages", List.of(
                                "sample.feature",
                                "package:sample.shared"
                        ),
                        "component_scan_base_package_classes", List.of(
                                "sample.feature.FeatureMarker"
                        )
                ))
                .build();

        ObservationResolutionResult result = resolver.resolve(
                contextOf(observation)
        );

        assertTrue(result.warnings().isEmpty());
        assertEquals(4, result.relations().size());

        Set<String> destinations = result.relations().stream()
                .map(RelationFact::dstRawRef)
                .collect(Collectors.toSet());

        assertEquals(
                Set.of(
                        "type:sample.SecurityConfig",
                        "type:sample.AuditConfig",
                        "package:sample.feature",
                        "package:sample.shared"
                ),
                destinations
        );

        for (RelationFact relation : result.relations()) {
            assertEquals(RelationKind.CONFIGURES_BEAN, relation.kind());
            assertEquals("type:sample.AppConfig", relation.srcSymbol());
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
                    "ConfigurationObservationResolver",
                    relation.attrs().get("resolver")
            );
            assertEquals(
                    "configuration_wiring",
                    relation.attrs().get("semantic_kind")
            );
        }

        Map<String, Map<String, Object>> attrsByDestination =
                result.relations().stream()
                        .collect(Collectors.toMap(
                                RelationFact::dstRawRef,
                                RelationFact::attrs
                        ));

        assertEquals(
                "import_type",
                attrsByDestination.get("type:sample.SecurityConfig")
                        .get("wiring_kind")
        );
        assertEquals(
                "component_scan_package",
                attrsByDestination.get("package:sample.feature")
                        .get("wiring_kind")
        );
    }

    @Test
    @DisplayName("대상이 없는 Configuration observation은 관계를 억지로 만들지 않는다")
    void keepsConfigurationWithoutTargetsAsObservationOnly() {
        ObservationFact observation = ObservationFact.builder()
                .kind(ObservationKind.CONFIG_WIRING)
                .siteSymbol("type:sample.LocalConfig")
                .attrs(Map.of(
                        "configuration_kind", "spring_configuration",
                        "imported_types", List.of(),
                        "component_scan_packages", List.of()
                ))
                .build();

        ObservationResolutionResult result = resolver.resolve(
                contextOf(observation)
        );

        assertTrue(result.relations().isEmpty());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    @DisplayName("siteSymbol이 없는 CONFIG_WIRING은 경고 후 건너뛴다")
    void skipsConfigurationWithoutSiteSymbol() {
        ObservationFact observation = ObservationFact.builder()
                .kind(ObservationKind.CONFIG_WIRING)
                .attrs(Map.of(
                        "imported_types", List.of("sample.SecurityConfig")
                ))
                .build();

        ObservationResolutionResult result = resolver.resolve(
                contextOf(observation)
        );

        assertTrue(result.relations().isEmpty());
        assertFalse(result.warnings().isEmpty());
        assertTrue(result.warnings().get(0).contains("siteSymbol"));
    }

    private ObservationResolutionContext contextOf(
            ObservationFact... observations
    ) {
        ExtractionAggregate aggregate = ExtractionAggregate.builder()
                .observations(ObservationTable.builder()
                        .configWiring(List.of(observations))
                        .build())
                .build();

        return ObservationResolutionContext.from(aggregate);
    }
}
