package com.example.ossdoc.domain.extraction.service.composer;

import com.example.ossdoc.domain.extraction.dto.context.FactsCompositionContext;
import com.example.ossdoc.domain.extraction.dto.model.BuildMeta;
import com.example.ossdoc.domain.extraction.dto.model.EvidenceFact;
import com.example.ossdoc.domain.extraction.dto.model.ExtractionAggregate;
import com.example.ossdoc.domain.extraction.dto.model.FactsDocument;
import com.example.ossdoc.domain.extraction.dto.model.JobMeta;
import com.example.ossdoc.domain.extraction.dto.model.ObservationFact;
import com.example.ossdoc.domain.extraction.dto.model.ObservationTable;
import com.example.ossdoc.domain.extraction.dto.model.StatsMeta;
import com.example.ossdoc.domain.extraction.enums.EvidenceType;
import com.example.ossdoc.domain.extraction.enums.ExtractionMode;
import com.example.ossdoc.domain.extraction.enums.FactOriginKind;
import com.example.ossdoc.domain.extraction.enums.ObservationKind;
import com.example.ossdoc.domain.extraction.enums.RelationKind;
import com.example.ossdoc.domain.extraction.enums.ResolutionStatus;
import com.example.ossdoc.domain.extraction.service.support.resolve.ConfigurationObservationResolver;
import com.example.ossdoc.domain.extraction.service.support.resolve.ObservationRelationResolutionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigurationRelationCompositionTest {

    @Test
    @DisplayName("Composer가 CONFIG_WIRING을 CONFIGURES_BEAN으로 승격해 relations와 stats에 반영한다")
    void composesConfigurationWiringRelations() {
        String evidenceId = "ev-config-wiring";

        EvidenceFact evidence = EvidenceFact.builder()
                .id(evidenceId)
                .type(EvidenceType.AST)
                .path("src/main/java/sample/AppConfig.java")
                .symbol("type:sample.AppConfig")
                .snippet("@Import(SecurityConfig.class)")
                .build();

        ObservationFact configuration = ObservationFact.builder()
                .kind(ObservationKind.CONFIG_WIRING)
                .siteSymbol("type:sample.AppConfig")
                .evidenceIds(List.of(evidenceId))
                .origin(FactOriginKind.AST)
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
                                "sample.AuditConfig"
                        ),
                        "component_scan_packages", List.of(
                                "sample.feature"
                        ),
                        "component_scan_base_package_classes", List.of()
                ))
                .build();

        ExtractionAggregate aggregate = ExtractionAggregate.builder()
                .evidence(Map.of(evidenceId, evidence))
                .observations(ObservationTable.builder()
                        .configWiring(List.of(configuration))
                        .build())
                .stats(StatsMeta.builder().build())
                .warnings(List.of())
                .build();

        ObservationRelationResolutionService resolutionService =
                new ObservationRelationResolutionService(
                        List.of(new ConfigurationObservationResolver())
                );

        DefaultFactsComposer composer = new DefaultFactsComposer(
                new FactsSectionFactory(),
                new FactsStatsCalculator(),
                resolutionService
        );

        OffsetDateTime now = OffsetDateTime.now();
        FactsCompositionContext context = new FactsCompositionContext(
                "test-schema",
                JobMeta.builder().build(),
                BuildMeta.builder().build(),
                ExtractionMode.AST_ONLY,
                now,
                now,
                List.of(),
                true,
                aggregate
        );

        FactsDocument document = composer.compose(context);

        assertNotNull(document);
        assertNotNull(document.relations());
        assertNotNull(document.relations().configuresBean());
        assertEquals(3, document.relations().configuresBean().size());

        var first = document.relations().configuresBean().get(0);
        assertEquals(RelationKind.CONFIGURES_BEAN, first.kind());
        assertEquals(
                ResolutionStatus.RESOLVED,
                first.resolution().status()
        );
        assertEquals(List.of(evidenceId), first.evidenceIds());

        assertEquals(3, document.stats().relations());
        assertEquals(1, document.stats().observations());
        assertTrue(document.extraction().warnings().isEmpty());

        ObjectMapper objectMapper = new ObjectMapper()
                .findAndRegisterModules();
        JsonNode json = objectMapper.valueToTree(document);

        JsonNode configuresBean = json.path("relations")
                .path("configures_bean");

        assertTrue(configuresBean.isArray());
        assertEquals(3, configuresBean.size());
        assertTrue(
                configuresBean.toString()
                        .contains("type:sample.SecurityConfig")
        );
        assertTrue(
                configuresBean.toString()
                        .contains("type:sample.AuditConfig")
        );
        assertTrue(
                configuresBean.toString()
                        .contains("package:sample.feature")
        );
    }
}
