package com.example.ossdoc.domain.extraction.service.support.resolve;

import com.example.ossdoc.domain.extraction.dto.model.ExtractionAggregate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 등록된 ObservationRelationResolver를 순서대로 실행하는 오케스트레이터.
 *
 * <p>아직 aggregate에 relation을 직접 삽입하지 않는다. Resolver 결과를
 * 반환하고, 다음 단계에서 파이프라인 적용 정책을 명시적으로 연결한다.</p>
 */
@Component
public class ObservationRelationResolutionService {

    private final List<ObservationRelationResolver> resolvers;

    public ObservationRelationResolutionService(
            List<ObservationRelationResolver> resolvers
    ) {
        List<ObservationRelationResolver> safeResolvers =
                resolvers == null
                        ? List.of()
                        : resolvers.stream()
                        .filter(Objects::nonNull)
                        .toList();

        List<ObservationRelationResolver> ordered =
                new ArrayList<>(safeResolvers);

        ordered.sort(
                Comparator.comparingInt(
                                ObservationRelationResolver::order
                        )
                        .thenComparing(
                                resolver -> resolver.getClass()
                                        .getName()
                        )
        );

        this.resolvers = List.copyOf(ordered);
    }

    public ObservationResolutionResult resolve(
            ExtractionAggregate aggregate
    ) {
        ObservationResolutionContext context =
                ObservationResolutionContext.from(aggregate);

        ObservationResolutionResult result =
                ObservationResolutionResult.empty();

        for (ObservationRelationResolver resolver : resolvers) {
            ObservationResolutionResult resolved;

            try {
                resolved = resolver.resolve(context);
            } catch (RuntimeException exception) {
                resolved = new ObservationResolutionResult(
                        List.of(),
                        List.of(
                                "Observation resolver failed: "
                                        + resolver.getClass().getSimpleName()
                                        + " ("
                                        + exception.getClass().getSimpleName()
                                        + ": "
                                        + Objects.toString(
                                        exception.getMessage(),
                                        "<no message>"
                                )
                                        + ")"
                        )
                );
            }

            result = result.merge(resolved);
        }

        return result;
    }

    public List<ObservationRelationResolver> resolvers() {
        return resolvers;
    }
}
