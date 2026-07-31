package com.example.ossdoc.domain.extraction.service.support.resolve;

import com.example.ossdoc.domain.extraction.dto.model.ExtractionAggregate;
import com.example.ossdoc.domain.extraction.dto.model.ObservationFact;
import com.example.ossdoc.domain.extraction.dto.model.ObservationTable;
import com.example.ossdoc.domain.extraction.dto.model.RelationFact;
import com.example.ossdoc.domain.extraction.dto.model.TypeRef;
import com.example.ossdoc.domain.extraction.enums.DerivationKind;
import com.example.ossdoc.domain.extraction.enums.FactOriginKind;
import com.example.ossdoc.domain.extraction.enums.ObservationKind;
import com.example.ossdoc.domain.extraction.enums.RelationKind;
import com.example.ossdoc.domain.extraction.enums.ResolutionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventObservationResolverTest {

    private final EventObservationResolver resolver = new EventObservationResolver();

    @Test
    @DisplayName("event 발행과 구독 observation을 각각 PUBLISHES_EVENT와 LISTENS_EVENT로 생성한다")
    void resolvesEventRelations() {
        ObservationFact publication = ObservationFact.builder()
                .kind(ObservationKind.EVENT_PUBLICATION)
                .siteSymbol("method:sample.OrderService#createOrder()")
                .targetTypeRef(typeRef("sample.OrderCreatedEvent", false))
                .evidenceIds(List.of("ev-publish"))
                .origin(FactOriginKind.AST)
                .confidenceHint(0.9)
                .attrs(Map.of("method", "publishEvent"))
                .build();

        ObservationFact subscription = ObservationFact.builder()
                .kind(ObservationKind.EVENT_SUBSCRIPTION)
                .siteSymbol("method:sample.OrderEventListener#handle(sample.OrderCreatedEvent)")
                .targetTypeRef(typeRef("sample.OrderCreatedEvent", false))
                .evidenceIds(List.of("ev-listen"))
                .origin(FactOriginKind.AST_AND_BYTECODE)
                .confidenceHint(0.9)
                .attrs(Map.of("annotations", List.of("EventListener")))
                .build();

        ObservationResolutionResult result = resolver.resolve(contextOf(
                List.of(publication),
                List.of(subscription)
        ));

        assertTrue(result.warnings().isEmpty());
        assertEquals(2, result.relations().size());

        RelationFact publishes = result.relations().stream()
                .filter(relation -> relation.kind() == RelationKind.PUBLISHES_EVENT)
                .findFirst()
                .orElseThrow();
        assertEquals("type:sample.OrderCreatedEvent", publishes.dstSymbol());
        assertEquals(ResolutionStatus.RESOLVED, publishes.resolution().status());
        assertEquals(DerivationKind.DERIVED, publishes.derivation());
        assertEquals(List.of("ev-publish"), publishes.evidenceIds());

        RelationFact listens = result.relations().stream()
                .filter(relation -> relation.kind() == RelationKind.LISTENS_EVENT)
                .findFirst()
                .orElseThrow();
        assertEquals("type:sample.OrderCreatedEvent", listens.dstSymbol());
        assertEquals(FactOriginKind.AST_AND_BYTECODE, listens.origin());
        assertEquals("event_subscription", listens.attrs().get("semantic_kind"));
    }

    @Test
    @DisplayName("event type이 unresolved면 관계를 버리지 않고 PARTIAL로 남긴다")
    void keepsUnresolvedEventAsPartialRelation() {
        ObservationFact publication = ObservationFact.builder()
                .kind(ObservationKind.EVENT_PUBLICATION)
                .siteSymbol("method:sample.OrderService#createOrder()")
                .targetTypeRef(typeRef("eventExpression", true))
                .build();

        ObservationResolutionResult result = resolver.resolve(contextOf(
                List.of(publication),
                List.of()
        ));

        RelationFact relation = result.relations().get(0);
        assertEquals(ResolutionStatus.PARTIAL, relation.resolution().status());
        assertEquals("event:eventExpression", relation.dstRawRef());
    }

    private ObservationResolutionContext contextOf(
            List<ObservationFact> publications,
            List<ObservationFact> subscriptions
    ) {
        return ObservationResolutionContext.from(
                ExtractionAggregate.builder()
                        .observations(ObservationTable.builder()
                                .eventPublications(publications)
                                .eventSubscriptions(subscriptions)
                                .build())
                        .build()
        );
    }

    private TypeRef typeRef(String raw, boolean unresolved) {
        return TypeRef.builder()
                .raw(raw)
                .unresolved(unresolved)
                .sourceText(raw)
                .build();
    }
}
