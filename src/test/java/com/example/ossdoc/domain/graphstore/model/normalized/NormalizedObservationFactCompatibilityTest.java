package com.example.ossdoc.domain.graphstore.model.normalized;

import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedObservationFact;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NormalizedObservationFactCompatibilityTest {

    @Test
    @DisplayName("기존 8개 인자 생성자는 origin null로 계속 동작한다")
    void oldConstructorRemainsCompatible() {
        NormalizedObservationFact fact =
                new NormalizedObservationFact(
                        "event_publication",
                        "method:sample.Service#publish()",
                        "type:sample.Event",
                        null,
                        null,
                        new BigDecimal("0.9"),
                        null,
                        List.of("event")
                );

        assertNull(fact.origin());
        assertEquals(
                List.of("event"),
                fact.evidenceIds()
        );
    }
}
