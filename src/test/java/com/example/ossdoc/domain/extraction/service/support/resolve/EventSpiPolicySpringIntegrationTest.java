package com.example.ossdoc.domain.extraction.service.support.resolve;

import com.example.ossdoc.domain.extraction.dto.model.ExtractionAggregate;
import com.example.ossdoc.domain.extraction.dto.model.ObservationFact;
import com.example.ossdoc.domain.extraction.dto.model.ObservationTable;
import com.example.ossdoc.domain.extraction.enums.FactOriginKind;
import com.example.ossdoc.domain.extraction.enums.ObservationKind;
import com.example.ossdoc.domain.extraction.service.support.policy.RelationConfidencePolicy;
import com.example.ossdoc.domain.extraction.service.support.policy.RelationResolutionPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

@ExtendWith(SpringExtension.class)
@Import({
        RelationResolutionPolicy.class,
        RelationConfidencePolicy.class,
        EventObservationResolver.class,
        SpiObservationResolver.class
})
class EventSpiPolicySpringIntegrationTest {

    @Autowired
    private RelationResolutionPolicy resolutionPolicy;

    @Autowired
    private RelationConfidencePolicy confidencePolicy;

    @Autowired
    private EventObservationResolver eventResolver;

    @Autowired
    private SpiObservationResolver spiResolver;

    @Test
    @DisplayName("Spring이 Event와 SPI Resolver에 같은 공통 정책 Bean을 주입한다")
    void injectsSharedPolicyBeans() {
        assertSame(
                resolutionPolicy,
                ReflectionTestUtils.getField(
                        eventResolver,
                        "resolutionPolicy"
                )
        );
        assertSame(
                confidencePolicy,
                ReflectionTestUtils.getField(
                        eventResolver,
                        "confidencePolicy"
                )
        );
        assertSame(
                resolutionPolicy,
                ReflectionTestUtils.getField(
                        spiResolver,
                        "resolutionPolicy"
                )
        );
        assertSame(
                confidencePolicy,
                ReflectionTestUtils.getField(
                        spiResolver,
                        "confidencePolicy"
                )
        );
    }

    @Test
    @DisplayName("공통 정책 Bean으로 Event와 SPI 관계 메타데이터를 동일 형식으로 생성한다")
    void createsUniformPolicyMetadata() {
        ObservationFact event = ObservationFact.builder()
                .kind(ObservationKind.EVENT_PUBLICATION)
                .siteSymbol("method:sample.OrderService#createOrder()")
                .targetSymbol("sample.OrderCreatedEvent")
                .origin(FactOriginKind.AST)
                .evidenceIds(List.of("ev-event"))
                .confidenceHint(0.9)
                .build();

        ObservationFact spi = ObservationFact.builder()
                .kind(ObservationKind.SPI_PROVIDER)
                .siteSymbol("method:sample.PluginRegistry#loadPlugins()")
                .targetSymbol("sample.Plugin")
                .origin(FactOriginKind.AST)
                .evidenceIds(List.of("ev-spi"))
                .confidenceHint(0.9)
                .build();

        ExtractionAggregate aggregate = ExtractionAggregate.builder()
                .observations(ObservationTable.builder()
                        .eventPublications(List.of(event))
                        .spiProviders(List.of(spi))
                        .build())
                .build();

        var eventRelation = eventResolver.resolve(
                ObservationResolutionContext.from(aggregate)
        ).relations().get(0);
        var spiRelation = spiResolver.resolve(
                ObservationResolutionContext.from(aggregate)
        ).relations().get(0);

        assertEquals("exact_symbol", eventRelation.attrs().get("resolution_basis"));
        assertEquals("exact_symbol", spiRelation.attrs().get("resolution_basis"));
        assertEquals("high", eventRelation.attrs().get("confidence_band"));
        assertEquals("high", spiRelation.attrs().get("confidence_band"));
        assertEquals(Boolean.TRUE, eventRelation.attrs().get("default_visible"));
        assertEquals(Boolean.TRUE, spiRelation.attrs().get("default_visible"));
        assertEquals(0.923, eventRelation.confidenceHint(), 0.0001);
        assertEquals(0.923, spiRelation.confidenceHint(), 0.0001);
    }
}
