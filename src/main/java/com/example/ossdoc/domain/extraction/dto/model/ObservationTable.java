package com.example.ossdoc.domain.extraction.dto.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.util.List;

/**
 * observation bucket 테이블
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ObservationTable(
        @JsonProperty("di_injection_sites")
        List<ObservationFact> diInjectionSites,

        @JsonProperty("di_providers")
        List<ObservationFact> diProviders,

        @JsonProperty("spi_providers")
        List<ObservationFact> spiProviders,

        @JsonProperty("event_publications")
        List<ObservationFact> eventPublications,

        @JsonProperty("event_subscriptions")
        List<ObservationFact> eventSubscriptions,

        @JsonProperty("reflection_sites")
        List<ObservationFact> reflectionSites,

        @JsonProperty("http_endpoints")
        List<ObservationFact> httpEndpoints,

        @JsonProperty("scheduling")
        List<ObservationFact> scheduling,

        @JsonProperty("async_methods")
        List<ObservationFact> asyncMethods,

        @JsonProperty("config_wiring")
        List<ObservationFact> configWiring
) {
}