package com.example.ossdoc.domain.graphstore.dto.facts.raw;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RawObservationTableDto {

    @JsonProperty("di_injection_sites")
    private List<RawObservationFactDto> diInjectionSites = new ArrayList<>();

    @JsonProperty("di_providers")
    private List<RawObservationFactDto> diProviders = new ArrayList<>();

    @JsonProperty("spi_providers")
    private List<RawObservationFactDto> spiProviders = new ArrayList<>();

    @JsonProperty("event_publications")
    private List<RawObservationFactDto> eventPublications = new ArrayList<>();

    @JsonProperty("event_subscriptions")
    private List<RawObservationFactDto> eventSubscriptions = new ArrayList<>();

    @JsonProperty("reflection_sites")
    private List<RawObservationFactDto> reflectionSites = new ArrayList<>();

    @JsonProperty("http_endpoints")
    private List<RawObservationFactDto> httpEndpoints = new ArrayList<>();

    @JsonProperty("scheduling")
    private List<RawObservationFactDto> scheduling = new ArrayList<>();

    @JsonProperty("async_methods")
    private List<RawObservationFactDto> asyncMethods = new ArrayList<>();

    @JsonProperty("config_wiring")
    private List<RawObservationFactDto> configWiring = new ArrayList<>();

    @JsonProperty("readme_mentions")
    private List<RawObservationFactDto> readmeMentions = new ArrayList<>();

    @JsonProperty("module_exports")
    private List<RawObservationFactDto> moduleExports = new ArrayList<>();

    @JsonProperty("module_uses")
    private List<RawObservationFactDto> moduleUses = new ArrayList<>();

    @JsonProperty("module_provides")
    private List<RawObservationFactDto> moduleProvides = new ArrayList<>();
}
