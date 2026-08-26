package com.example.ossdoc.domain.graphstore.model.promotion;

/**
 * GraphStore 의미 Relation shadow 후보 생성 단위.
 */
public enum SemanticPromotionShadowStage {

    ENDPOINT_EVENT_SPI("endpoint_event_spi"),
    BEAN_CONFIGURATION("bean_configuration"),
    REFLECTION("reflection"),
    DI("di");

    private final String code;

    SemanticPromotionShadowStage(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
