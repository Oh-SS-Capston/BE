package com.example.ossdoc.domain.extraction.service.composer;

import com.example.ossdoc.domain.extraction.dto.context.FactsCompositionContext;
import com.example.ossdoc.domain.extraction.dto.model.BuildMeta;
import com.example.ossdoc.domain.extraction.dto.model.ExtractionAggregate;
import com.example.ossdoc.domain.extraction.dto.model.FactsDocument;
import com.example.ossdoc.domain.extraction.dto.model.JobMeta;
import com.example.ossdoc.domain.extraction.dto.model.ObservationFact;
import com.example.ossdoc.domain.extraction.dto.model.ObservationTable;
import com.example.ossdoc.domain.extraction.dto.model.StatsMeta;
import com.example.ossdoc.domain.extraction.dto.model.TypeRef;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventSpiRelationCompositionTest {

    @Test
    @DisplayName("Event와 SPI observation을 최종 relations JSON과 stats에 함께 반영한다")
    void composesEventAndSpiRelations() {
        ObservationFact publication = ObservationFact.builder()
                .kind(ObservationKind.EVENT_PUBLICATION)
                .siteSymbol("method:sample.OrderService#createOrder()")
                .targetTypeRef(typeRef("sample.OrderCreatedEvent"))
                .origin(FactOriginKind.AST)
                .build();

        ObservationFact subscription = ObservationFact.builder()
                .kind(ObservationKind.EVENT_SUBSCRIPTION)
                .siteSymbol("method:sample.OrderListener#handle(sample.OrderCreatedEvent)")
                .targetTypeRef(typeRef("sample.OrderCreatedEvent"))
                .origin(FactOriginKind.AST)
                .build();

        ObservationFact serviceLoader = ObservationFact.builder()
                .kind(ObservationKind.SPI_PROVIDER)
                .siteSymbol("method:sample.PluginRegistry#loadPlugins()")
                .targetSymbol("sample.Plugin")
                .origin(FactOriginKind.AST)
                .build();

        ObservationFact moduleProvides = ObservationFact.builder()
                .kind(ObservationKind.MODULE_PROVIDES)
                .siteSymbol("module:sample.plugin")
                .targetSymbol("sample.Plugin")
                .origin(FactOriginKind.AST)
                .attrs(Map.of("implementation", "sample.DefaultPlugin"))
                .build();

        ExtractionAggregate aggregate = ExtractionAggregate.builder()
                .observations(ObservationTable.builder()
                        .eventPublications(List.of(publication))
                        .eventSubscriptions(List.of(subscription))
                        .spiProviders(List.of(serviceLoader))
                        .moduleProvides(List.of(moduleProvides))
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
        assertEquals(1, document.relations().listensEvent().size());
        assertEquals(1, document.relations().providesSpi().size());
        assertEquals(1, document.relations().loadsService().size());
        assertEquals(4, document.stats().relations());
        assertEquals(4, document.stats().observations());
        assertTrue(document.extraction().warnings().isEmpty());

        JsonNode json = new ObjectMapper()
                .findAndRegisterModules()
                .valueToTree(document);
        assertEquals(1, json.path("relations").path("publishes_event").size());
        assertEquals(1, json.path("relations").path("listens_event").size());
        assertEquals(1, json.path("relations").path("provides_spi").size());
        assertEquals(1, json.path("relations").path("loads_service").size());
    }

    private TypeRef typeRef(String raw) {
        return TypeRef.builder()
                .raw(raw)
                .sourceText(raw)
                .unresolved(false)
                .build();
    }
}
