package com.example.ossdoc.domain.extraction.service.composer;

import com.example.ossdoc.domain.extraction.dto.context.FactsCompositionContext;
import com.example.ossdoc.domain.extraction.dto.model.BuildMeta;
import com.example.ossdoc.domain.extraction.dto.model.ExtractionAggregate;
import com.example.ossdoc.domain.extraction.dto.model.FactsDocument;
import com.example.ossdoc.domain.extraction.dto.model.JobMeta;
import com.example.ossdoc.domain.extraction.dto.model.ObservationFact;
import com.example.ossdoc.domain.extraction.dto.model.ObservationTable;
import com.example.ossdoc.domain.extraction.dto.model.StatsMeta;
import com.example.ossdoc.domain.extraction.enums.ExtractionMode;
import com.example.ossdoc.domain.extraction.enums.FactOriginKind;
import com.example.ossdoc.domain.extraction.enums.ObservationKind;
import com.example.ossdoc.domain.extraction.service.support.resolve.EventObservationResolver;
import com.example.ossdoc.domain.extraction.service.support.resolve.ObservationRelationResolutionService;
import com.example.ossdoc.domain.extraction.service.support.resolve.SpiObservationResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventSpiPolicyCompositionTest {

    @Test
    @DisplayName("Event와 SPI 공통 정책 메타데이터를 최종 facts JSON에 보존한다")
    void preservesPolicyMetadataInFactsDocument() {
        ObservationFact publication = ObservationFact.builder()
                .kind(ObservationKind.EVENT_PUBLICATION)
                .siteSymbol("method:sample.OrderService#createOrder()")
                .targetSymbol("sample.OrderCreatedEvent")
                .origin(FactOriginKind.AST)
                .evidenceIds(List.of("ev-event"))
                .confidenceHint(0.9)
                .build();

        ObservationFact serviceLoader = ObservationFact.builder()
                .kind(ObservationKind.SPI_PROVIDER)
                .siteSymbol("method:sample.PluginRegistry#loadPlugins()")
                .targetSymbol("sample.Plugin")
                .origin(FactOriginKind.AST_AND_BYTECODE)
                .evidenceIds(List.of("ev-spi"))
                .confidenceHint(0.9)
                .build();

        ExtractionAggregate aggregate = ExtractionAggregate.builder()
                .observations(ObservationTable.builder()
                        .eventPublications(List.of(publication))
                        .spiProviders(List.of(serviceLoader))
                        .build())
                .stats(StatsMeta.builder().build())
                .warnings(List.of())
                .build();

        ObservationRelationResolutionService resolutionService =
                new ObservationRelationResolutionService(List.of(
                        new EventObservationResolver(),
                        new SpiObservationResolver()
                ));

        DefaultFactsComposer composer = new DefaultFactsComposer(
                new FactsSectionFactory(),
                new FactsStatsCalculator(),
                resolutionService
        );

        OffsetDateTime now = OffsetDateTime.now();
        FactsDocument document = composer.compose(new FactsCompositionContext(
                "test-schema",
                JobMeta.builder().build(),
                BuildMeta.builder().build(),
                ExtractionMode.AST_ONLY,
                now,
                now,
                List.of(),
                true,
                aggregate
        ));

        assertEquals(1, document.relations().publishesEvent().size());
        assertEquals(1, document.relations().loadsService().size());
        assertEquals(2, document.stats().relations());

        JsonNode json = new ObjectMapper()
                .findAndRegisterModules()
                .valueToTree(document);

        JsonNode eventAttrs = json.path("relations")
                .path("publishes_event")
                .get(0)
                .path("attrs");
        JsonNode spiAttrs = json.path("relations")
                .path("loads_service")
                .get(0)
                .path("attrs");

        assertEquals("exact_symbol", eventAttrs.path("resolution_basis").asText());
        assertEquals("high", eventAttrs.path("confidence_band").asText());
        assertTrue(eventAttrs.path("default_visible").asBoolean());

        assertEquals("exact_symbol", spiAttrs.path("resolution_basis").asText());
        assertEquals("high", spiAttrs.path("confidence_band").asText());
        assertTrue(spiAttrs.path("default_visible").asBoolean());
    }
}
