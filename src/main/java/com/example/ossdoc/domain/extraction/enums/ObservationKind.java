package com.example.ossdoc.domain.extraction.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 비정적 연결을 위한 관측 사실 종류
 */
public enum ObservationKind {
    DI_INJECTION_SITE("di_injection_site"),
    DI_PROVIDER("di_provider"),
    SPI_PROVIDER("spi_provider"),
    EVENT_PUBLISH("event_publish"),
    EVENT_SUBSCRIBE("event_subscribe"),
    REFLECTION_USE("reflection_use"),
    CONFIG_WIRING("config_wiring");

    private final String code;

    ObservationKind(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}