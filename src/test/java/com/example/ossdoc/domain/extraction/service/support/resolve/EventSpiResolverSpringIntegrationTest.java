package com.example.ossdoc.domain.extraction.service.support.resolve;

import com.example.ossdoc.domain.extraction.dto.model.ExtractionAggregate;
import com.example.ossdoc.domain.extraction.dto.model.ObservationFact;
import com.example.ossdoc.domain.extraction.dto.model.ObservationTable;
import com.example.ossdoc.domain.extraction.dto.model.TypeRef;
import com.example.ossdoc.domain.extraction.enums.ObservationKind;
import com.example.ossdoc.domain.extraction.enums.RelationKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(SpringExtension.class)
@Import({
        ObservationRelationResolutionService.class,
        EventObservationResolver.class,
        SpiObservationResolver.class
})
class EventSpiResolverSpringIntegrationTest {

    @Autowired
    private ObservationRelationResolutionService resolutionService;

    @Test
    @DisplayName("Spring이 Event와 SPI Resolver를 순서대로 등록해 함께 실행한다")
    void registersAndRunsEventAndSpiResolvers() {
        ObservationFact publication = ObservationFact.builder()
                .kind(ObservationKind.EVENT_PUBLICATION)
                .siteSymbol("method:sample.OrderService#createOrder()")
                .targetTypeRef(TypeRef.builder()
                        .raw("sample.OrderCreatedEvent")
                        .unresolved(false)
                        .build())
                .build();

        ObservationFact serviceLoader = ObservationFact.builder()
                .kind(ObservationKind.SPI_PROVIDER)
                .siteSymbol("method:sample.PluginRegistry#loadPlugins()")
                .targetSymbol("sample.Plugin")
                .build();

        ExtractionAggregate aggregate = ExtractionAggregate.builder()
                .observations(ObservationTable.builder()
                        .eventPublications(List.of(publication))
                        .spiProviders(List.of(serviceLoader))
                        .build())
                .build();

        ObservationResolutionResult result = resolutionService.resolve(aggregate);

        assertEquals(
                List.of(
                        EventObservationResolver.class,
                        SpiObservationResolver.class
                ),
                resolutionService.resolvers().stream()
                        .map(Object::getClass)
                        .toList()
        );
        assertEquals(2, result.relations().size());
        assertEquals(RelationKind.PUBLISHES_EVENT, result.relations().get(0).kind());
        assertEquals(RelationKind.LOADS_SERVICE, result.relations().get(1).kind());
    }
}
