package com.example.ossdoc.domain.graphstore.service.promotion;

import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedFactsDocument;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedObservationFact;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedRelationFact;
import com.example.ossdoc.domain.graphstore.model.promotion.ObservationPromotionCandidateGenerationResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BeanConfigurationShadowCandidateGeneratorTest {

    private final ObjectMapper objectMapper =
            new ObjectMapper()
                    .findAndRegisterModules();

    @Test
    @DisplayName("선언된 Bean 이름마다 DECLARES_BEAN 후보를 생성한다")
    void generatesDeclaredBeanNames() {
        var attrs =
                JsonNodeFactory.instance.objectNode();

        attrs.putArray("bean_names")
                .add("objectMapper")
                .add("customMapper");

        attrs.put(
                "provided_type",
                "com.fasterxml.jackson.databind.ObjectMapper"
        );

        attrs.put(
                "provider_kind",
                "provider_method"
        );

        ObservationPromotionCandidateGenerationResult result =
                generate(List.of(observation(
                        "di_provider",
                        "method:sample.AppConfig#objectMapper()",
                        null,
                        null,
                        "ast",
                        attrs,
                        List.of("bean-annotation")
                )));

        assertEquals(1, result.eligibleObservationCount());
        assertEquals(2, result.candidates().size());
        assertTrue(result.warnings().isEmpty());

        assertEquals(
                List.of(
                        "bean:objectMapper",
                        "bean:customMapper"
                ),
                result.candidates().stream()
                        .map(candidate ->
                                candidate.relation()
                                        .dstRawRef()
                        )
                        .toList()
        );

        for (var candidate : result.candidates()) {
            NormalizedRelationFact relation =
                    candidate.relation();

            assertEquals(
                    "declares_bean",
                    relation.kind()
            );
            assertEquals(
                    "method:sample.AppConfig#objectMapper()",
                    relation.srcSymbol()
            );
            assertEquals(
                    "declared",
                    relation.attrs()
                            .get("name_resolution")
            );
            assertEquals(
                    "bean_declaration",
                    relation.attrs()
                            .get("semantic_kind")
            );
            assertEquals(
                    "BeanObservationResolver",
                    relation.attrs().get("resolver")
            );
            assertEquals(
                    List.of("bean-annotation"),
                    relation.evidenceIds()
            );
        }
    }

    @Test
    @DisplayName("Bean 이름은 provided type을 provider method 이름보다 먼저 사용해 추론한다")
    void infersBeanNameFromProvidedTypeFirst() {
        JsonNode targetTypeRef =
                JsonNodeFactory.instance
                        .objectNode()
                        .put(
                                "raw",
                                "sample.PaymentService"
                        );

        NormalizedRelationFact relation =
                generate(List.of(observation(
                        "di_provider",
                        "method:sample.AppConfig#paymentClient()",
                        null,
                        targetTypeRef,
                        "ast",
                        null,
                        List.of("bean")
                ))).candidates()
                        .get(0)
                        .relation();

        assertEquals(
                "bean:paymentService",
                relation.dstRawRef()
        );
        assertEquals(
                "inferred",
                relation.attrs()
                        .get("name_resolution")
        );
        assertEquals(
                "Bean name inferred from provided type",
                relation.resolutionReason()
        );
    }

    @Test
    @DisplayName("provided type이 없으면 provider symbol에서 Bean 이름을 추론한다")
    void infersBeanNameFromProviderSymbol() {
        NormalizedRelationFact relation =
                generate(List.of(observation(
                        "di_provider",
                        "method:sample.AppConfig#clock()",
                        null,
                        null,
                        "observed",
                        null,
                        List.of()
                ))).candidates()
                        .get(0)
                        .relation();

        assertEquals(
                "bean:clock",
                relation.dstRawRef()
        );
        assertEquals(
                "Bean name inferred from provider symbol",
                relation.resolutionReason()
        );
    }

    @Test
    @DisplayName("Bean 이름을 결정할 수 없으면 후보 없이 warning을 남긴다")
    void warnsWhenBeanNameCannotBeDetermined() {
        ObservationPromotionCandidateGenerationResult result =
                generate(List.of(observation(
                        "di_provider",
                        "resource:provider",
                        null,
                        null,
                        "resource",
                        null,
                        List.of()
                )));

        assertTrue(result.candidates().isEmpty());
        assertEquals(1, result.warnings().size());
        assertTrue(
                result.warnings().get(0)
                        .contains(
                                "could not determine a Bean name"
                        )
        );
    }

    @Test
    @DisplayName("imported type과 component scan package마다 CONFIGURES_BEAN 후보를 생성한다")
    void generatesConfigurationWiringCandidates() {
        var attrs =
                JsonNodeFactory.instance.objectNode();

        attrs.putArray("imported_types")
                .add("type:sample.ImportedConfig")
                .add("sample.FeatureConfig.class")
                .add("sample.ImportedConfig");

        attrs.putArray("component_scan_packages")
                .add("package:sample.feature.")
                .add("sample.shared")
                .add("sample.feature");

        attrs.put(
                "configuration_kind",
                "configuration"
        );

        ObservationPromotionCandidateGenerationResult result =
                generate(List.of(observation(
                        "config_wiring",
                        "type:sample.AppConfig",
                        null,
                        null,
                        "ast",
                        attrs,
                        List.of("config-annotation")
                )));

        assertEquals(1, result.eligibleObservationCount());
        assertEquals(4, result.candidates().size());

        assertEquals(
                List.of(
                        "type:sample.ImportedConfig",
                        "type:sample.FeatureConfig",
                        "package:sample.feature",
                        "package:sample.shared"
                ),
                result.candidates().stream()
                        .map(candidate ->
                                candidate.relation()
                                        .dstRawRef()
                        )
                        .toList()
        );

        assertEquals(
                List.of(
                        "import_type",
                        "import_type",
                        "component_scan_package",
                        "component_scan_package"
                ),
                result.candidates().stream()
                        .map(candidate ->
                                candidate.relation()
                                        .attrs()
                                        .get("wiring_kind")
                        )
                        .toList()
        );

        for (var candidate : result.candidates()) {
            assertEquals(
                    "configures_bean",
                    candidate.relation().kind()
            );
            assertEquals(
                    "configuration_wiring",
                    candidate.relation()
                            .attrs()
                            .get("semantic_kind")
            );
            assertEquals(
                    "ConfigurationObservationResolver",
                    candidate.relation()
                            .attrs()
                            .get("resolver")
            );
        }
    }

    @Test
    @DisplayName("빈 Configuration wiring 목록은 Relation을 만들지 않는다")
    void emptyConfigurationProducesNoCandidate() {
        ObservationPromotionCandidateGenerationResult result =
                generate(List.of(observation(
                        "config_wiring",
                        "type:sample.AppConfig",
                        null,
                        null,
                        "ast",
                        JsonNodeFactory.instance.objectNode(),
                        List.of()
                )));

        assertEquals(1, result.eligibleObservationCount());
        assertTrue(result.candidates().isEmpty());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    @DisplayName("다른 Observation 종류는 10-3-3B 후보 생성에서 제외한다")
    void ignoresOtherObservationKinds() {
        ObservationPromotionCandidateGenerationResult result =
                generate(List.of(observation(
                        "event_publication",
                        "method:sample.Service#publish()",
                        "type:sample.Event",
                        null,
                        "observed",
                        null,
                        List.of()
                )));

        assertEquals(0, result.eligibleObservationCount());
        assertTrue(result.candidates().isEmpty());
    }

    private ObservationPromotionCandidateGenerationResult generate(
            List<NormalizedObservationFact> observations
    ) {
        return BeanConfigurationShadowCandidateGenerator
                .generate(
                        new NormalizedFactsDocument(
                                "2",
                                Map.of(),
                                List.of(),
                                List.of(),
                                observations
                        ),
                        objectMapper
                );
    }

    private NormalizedObservationFact observation(
            String kind,
            String siteSymbol,
            String targetSymbol,
            JsonNode targetTypeRef,
            String origin,
            JsonNode attrs,
            List<String> evidenceIds
    ) {
        return new NormalizedObservationFact(
                kind,
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
}
