package com.example.ossdoc.domain.extraction.service.composer;

import com.example.ossdoc.domain.extraction.dto.model.ObservationFact;
import com.example.ossdoc.domain.extraction.dto.model.ObservationTable;
import com.example.ossdoc.domain.extraction.enums.ObservationKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReflectionObservationDedupTest {

    @Test
    @DisplayName("같은 메서드 안의 서로 다른 reflection 호출을 별도 observation으로 유지한다")
    void keepsDistinctReflectionCalls() {
        ObservationFact first = observation(
                "sample.First",
                "first"
        );
        ObservationFact second = observation(
                "sample.Second",
                "second"
        );

        ObservationTable result = new FactsSectionFactory()
                .composeObservations(ObservationTable.builder()
                        .reflectionSites(List.of(first, second))
                        .build());

        assertEquals(2, result.reflectionSites().size());
    }

    private ObservationFact observation(
            String targetType,
            String memberName
    ) {
        return ObservationFact.builder()
                .kind(ObservationKind.REFLECTION_SITE)
                .siteSymbol("method:sample.Client#reflect()")
                .attrs(Map.of(
                        "api_method", "getDeclaredMethod",
                        "reflection_kind", "method",
                        "target_type", targetType,
                        "member_name", memberName
                ))
                .build();
    }
}
