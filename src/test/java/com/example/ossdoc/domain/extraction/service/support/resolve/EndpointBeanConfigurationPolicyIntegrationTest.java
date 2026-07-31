package com.example.ossdoc.domain.extraction.service.support.resolve;

import com.example.ossdoc.domain.extraction.dto.model.ExtractionAggregate;
import com.example.ossdoc.domain.extraction.dto.model.ObservationFact;
import com.example.ossdoc.domain.extraction.dto.model.ObservationTable;
import com.example.ossdoc.domain.extraction.enums.FactOriginKind;
import com.example.ossdoc.domain.extraction.enums.ObservationKind;
import com.example.ossdoc.domain.extraction.enums.ResolutionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndpointBeanConfigurationPolicyIntegrationTest {

    @Test
    @DisplayName("Endpoint·Bean·Configuration Resolver가 동일한 정책 메타데이터를 출력한다")
    void appliesCommonPolicyMetadata() {
        ObservationResolutionContext context = ObservationResolutionContext.from(
                ExtractionAggregate.builder()
                        .observations(ObservationTable.builder()
                                .httpEndpoints(List.of(endpoint()))
                                .diProviders(List.of(provider()))
                                .configWiring(List.of(configuration()))
                                .build())
                        .build()
        );

        var endpointResult = new EndpointObservationResolver().resolve(context);
        var beanResult = new BeanObservationResolver().resolve(context);
        var configResult = new ConfigurationObservationResolver().resolve(context);

        assertTrue(endpointResult.warnings().isEmpty());
        assertTrue(beanResult.warnings().isEmpty());
        assertTrue(configResult.warnings().isEmpty());

        for (var relation : List.of(
                endpointResult.relations().get(0),
                beanResult.relations().get(0),
                configResult.relations().get(0)
        )) {
            assertEquals(ResolutionStatus.RESOLVED, relation.resolution().status());
            assertEquals("exact_reference", relation.attrs().get("resolution_basis"));
            assertEquals("high", relation.attrs().get("confidence_band"));
            assertEquals(true, relation.attrs().get("default_visible"));
        }
    }

    private ObservationFact endpoint() {
        return ObservationFact.builder()
                .kind(ObservationKind.HTTP_ENDPOINT)
                .siteSymbol("method:sample.Controller#find()")
                .origin(FactOriginKind.AST)
                .confidenceHint(0.9)
                .evidenceIds(List.of("ev-endpoint"))
                .attrs(Map.of(
                        "http_methods", List.of("GET"),
                        "paths", List.of("/items"),
                        "path_resolution", "resolved"
                ))
                .build();
    }

    private ObservationFact provider() {
        return ObservationFact.builder()
                .kind(ObservationKind.DI_PROVIDER)
                .siteSymbol("method:sample.Config#service()")
                .origin(FactOriginKind.AST)
                .confidenceHint(0.9)
                .evidenceIds(List.of("ev-bean"))
                .attrs(Map.of(
                        "bean_names", List.of("service"),
                        "provided_type", "sample.Service"
                ))
                .build();
    }

    private ObservationFact configuration() {
        return ObservationFact.builder()
                .kind(ObservationKind.CONFIG_WIRING)
                .siteSymbol("type:sample.Config")
                .origin(FactOriginKind.AST)
                .confidenceHint(0.9)
                .evidenceIds(List.of("ev-config"))
                .attrs(Map.of(
                        "imported_types", List.of("sample.SecurityConfig"),
                        "component_scan_packages", List.of()
                ))
                .build();
    }
}
