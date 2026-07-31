package com.example.ossdoc.domain.extraction.service.support.resolve;

import com.example.ossdoc.domain.extraction.dto.model.ExtractionAggregate;
import com.example.ossdoc.domain.extraction.dto.model.RelationFact;
import com.example.ossdoc.domain.extraction.enums.FactOriginKind;
import com.example.ossdoc.domain.extraction.enums.ObservationKind;
import com.example.ossdoc.domain.extraction.enums.RelationKind;
import com.example.ossdoc.domain.extraction.service.support.util.RelationResolutionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObservationRelationResolutionServiceTest {

    @Test
    @DisplayName("resolver는 order 순서대로 실행하고 결과와 경고를 병합한다")
    void resolve_executesResolversInOrderAndMergesResults() {
        ObservationRelationResolver second =
                new StubResolver(
                        20,
                        "method:sample.Second#run()",
                        "type:sample.SecondTarget",
                        "second warning"
                );

        ObservationRelationResolver first =
                new StubResolver(
                        10,
                        "method:sample.First#run()",
                        "type:sample.FirstTarget",
                        "first warning"
                );

        ObservationRelationResolutionService service =
                new ObservationRelationResolutionService(
                        List.of(second, first)
                );

        ExtractionAggregate aggregate =
                ExtractionAggregate.builder()
                        .build();

        ObservationResolutionResult result =
                service.resolve(aggregate);

        assertEquals(2, result.relations().size());
        assertEquals(
                "method:sample.First#run()",
                result.relations().get(0).srcSymbol()
        );
        assertEquals(
                "method:sample.Second#run()",
                result.relations().get(1).srcSymbol()
        );
        assertEquals(
                List.of("first warning", "second warning"),
                result.warnings()
        );
    }

    @Test
    @DisplayName("resolver 예외는 전체 실행을 중단하지 않고 warning으로 변환한다")
    void resolve_convertsResolverFailureToWarning() {
        ObservationRelationResolver failing =
                new ObservationRelationResolver() {
                    @Override
                    public Set<ObservationKind> supportedKinds() {
                        return Set.of(ObservationKind.HTTP_ENDPOINT);
                    }

                    @Override
                    public ObservationResolutionResult resolve(
                            ObservationResolutionContext context
                    ) {
                        throw new IllegalStateException("boom");
                    }
                };

        ObservationRelationResolutionService service =
                new ObservationRelationResolutionService(
                        List.of(failing)
                );

        ObservationResolutionResult result =
                service.resolve(
                        ExtractionAggregate.builder().build()
                );

        assertTrue(result.relations().isEmpty());
        assertEquals(1, result.warnings().size());
        assertTrue(
                result.warnings().get(0)
                        .contains("IllegalStateException")
        );
    }

    private record StubResolver(
            int resolverOrder,
            String source,
            String destination,
            String warning
    ) implements ObservationRelationResolver {

        @Override
        public Set<ObservationKind> supportedKinds() {
            return Set.of(ObservationKind.HTTP_ENDPOINT);
        }

        @Override
        public int order() {
            return resolverOrder;
        }

        @Override
        public ObservationResolutionResult resolve(
                ObservationResolutionContext context
        ) {
            RelationFact relation =
                    RelationFact.builder()
                            .kind(RelationKind.CALLS)
                            .srcSymbol(source)
                            .dstSymbol(destination)
                            .resolution(
                                    RelationResolutionFactory.resolved()
                            )
                            .origin(FactOriginKind.AST)
                            .build();

            return new ObservationResolutionResult(
                    List.of(relation),
                    List.of(warning)
            );
        }
    }
}
