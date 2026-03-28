package com.example.ossdoc.domain.extraction.service.support.util;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 테스트 가능하게 분리한 시간 공급자
 */
@Component
public class ExtractionClock {

    private final Clock clock;

    public ExtractionClock() {
        this(Clock.systemUTC());
    }

    public ExtractionClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    public OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
