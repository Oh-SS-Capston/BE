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
import com.example.ossdoc.domain.extraction.dto.model.TypeRef;
import com.example.ossdoc.domain.extraction.enums.EvidenceType;
import com.example.ossdoc.domain.extraction.enums.ExtractionMode;
import com.example.ossdoc.domain.extraction.enums.FactOriginKind;
import com.example.ossdoc.domain.extraction.enums.ObservationKind;
import com.example.ossdoc.domain.extraction.enums.RelationKind;
import com.example.ossdoc.domain.extraction.enums.ResolutionStatus;
import com.example.ossdoc.domain.extraction.service.support.resolve.BeanObservationResolver;
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

class BeanRelationCompositionTest {

    @Test
    @DisplayName("Composer가 DI_PROVIDER를 DECLARES_BEAN으로 승격해 relations와 stats에 반영한다")
    void composesDeclaredBeanRelations() {
        String evidenceId = "ev-bean-provider";

        EvidenceFact evidence = EvidenceFact.builder()
                .id(evidenceId)
                .type(EvidenceType.AST)
                .path("src/main/java/sample/AppConfig.java")
                .symbol("method:sample.AppConfig#objectMapper()")
                .snippet("@Bean(name = {\"apiMapper\", \"objectMapper\"})")
                .build();

        TypeRef providedType = TypeRef.builder()
                .raw("sample.ObjectMapper")
                .arrayDim(0)
                .primitive(false)
                .unresolved(false)
                .build();

        ObservationFact provider = ObservationFact.builder()
                .kind(ObservationKind.DI_PROVIDER)
                .siteSymbol("method:sample.AppConfig#objectMapper()")
                .targetTypeRef(providedType)
                .evidenceIds(List.of(evidenceId))
                .origin(FactOriginKind.AST)
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

        ExtractionAggregate aggregate = ExtractionAggregate.builder()
                .evidence(Map.of(evidenceId, evidence))
                .observations(ObservationTable.builder()
                        .diProviders(List.of(provider))
                        .build())
                .stats(StatsMeta.builder().build())
                .warnings(List.of())
                .build();

        ObservationRelationResolutionService resolutionService =
                new ObservationRelationResolutionService(
                        List.of(new BeanObservationResolver())
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
        assertNotNull(document.relations().declaresBean());
        assertEquals(2, document.relations().declaresBean().size());

        var first = document.relations().declaresBean().get(0);
        assertEquals(RelationKind.DECLARES_BEAN, first.kind());
        assertEquals(
                ResolutionStatus.RESOLVED,
                first.resolution().status()
        );
        assertEquals(List.of(evidenceId), first.evidenceIds());

        assertEquals(2, document.stats().relations());
        assertEquals(1, document.stats().observations());
        assertTrue(document.extraction().warnings().isEmpty());

        ObjectMapper objectMapper = new ObjectMapper()
                .findAndRegisterModules();
        JsonNode json = objectMapper.valueToTree(document);

        JsonNode declaresBean = json.path("relations")
                .path("declares_bean");

        assertTrue(declaresBean.isArray());
        assertEquals(2, declaresBean.size());
        assertTrue(
                declaresBean.toString().contains("bean:apiMapper")
        );
        assertTrue(
                declaresBean.toString().contains("bean:objectMapper")
        );
    }
}
