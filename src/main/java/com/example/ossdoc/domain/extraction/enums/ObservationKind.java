package com.example.ossdoc.domain.extraction.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 비정적 연결을 위한 관측 사실 종류
 */
public enum ObservationKind {
    DI_INJECTION_SITE("di_injection_site"),
    DI_PROVIDER("di_provider"),
    SPI_PROVIDER("spi_provider"),
    EVENT_PUBLICATION("event_publication"),
    EVENT_SUBSCRIPTION("event_subscription"),
    REFLECTION_SITE("reflection_site"),
    HTTP_ENDPOINT("http_endpoint"),
    SCHEDULED_TASK("scheduled_task"),
    ASYNC_METHOD("async_method"),
    CONFIG_WIRING("config_wiring"),
    README_MENTION("readme_mention"),
    MODULE_EXPORTS("module_exports"),
    MODULE_USES("module_uses"),
    MODULE_PROVIDES("module_provides");

    private final String code;

    ObservationKind(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}