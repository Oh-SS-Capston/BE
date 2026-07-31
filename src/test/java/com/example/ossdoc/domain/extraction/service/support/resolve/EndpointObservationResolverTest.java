package com.example.ossdoc.domain.extraction.service.support.resolve;

import com.example.ossdoc.domain.extraction.dto.model.ExtractionAggregate;
import com.example.ossdoc.domain.extraction.dto.model.ObservationFact;
import com.example.ossdoc.domain.extraction.dto.model.ObservationTable;
import com.example.ossdoc.domain.extraction.dto.model.RelationFact;
import com.example.ossdoc.domain.extraction.enums.DerivationKind;
import com.example.ossdoc.domain.extraction.enums.FactOriginKind;
import com.example.ossdoc.domain.extraction.enums.ObservationKind;
import com.example.ossdoc.domain.extraction.enums.RelationKind;
import com.example.ossdoc.domain.extraction.enums.ResolutionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndpointObservationResolverTest {

    private final EndpointObservationResolver resolver =
            new EndpointObservationResolver();

    @Test
    @DisplayName("복수 HTTP method와 path를 조합해 HANDLES_ENDPOINT를 생성한다")
    void resolvesMethodAndPathCombinations() {
        ObservationFact observation = ObservationFact.builder()
                .kind(ObservationKind.HTTP_ENDPOINT)
                .siteSymbol("method:sample.UserController#find()")
                .evidenceIds(List.of("ev-method", "ev-class"))
                .origin(FactOriginKind.AST)
                .confidenceHint(0.9)
                .attrs(Map.of(
                        "framework", "spring_mvc",
                        "http_methods", List.of("GET", "POST"),
                        "paths", List.of("/users", "/members"),
                        "path_resolution", "resolved",
                        "produces", List.of("application/json")
                ))
                .build();

        ObservationResolutionResult result = resolver.resolve(
                contextOf(observation)
        );

        assertTrue(result.warnings().isEmpty());
        assertEquals(4, result.relations().size());

        Set<String> destinations = result.relations().stream()
                .map(RelationFact::dstRawRef)
                .collect(Collectors.toSet());

        assertEquals(
                Set.of(
                        "GET /users",
                        "GET /members",
                        "POST /users",
                        "POST /members"
                ),
                destinations
        );

        for (RelationFact relation : result.relations()) {
            assertEquals(RelationKind.HANDLES_ENDPOINT, relation.kind());
            assertEquals(
                    "method:sample.UserController#find()",
                    relation.srcSymbol()
            );
            assertEquals(
                    ResolutionStatus.RESOLVED,
                    relation.resolution().status()
            );
            assertEquals(DerivationKind.DERIVED, relation.derivation());
            assertEquals(FactOriginKind.AST, relation.origin());
            assertEquals(
                    List.of("ev-method", "ev-class"),
                    relation.evidenceIds()
            );
            assertEquals(
                    "EndpointObservationResolver",
                    relation.attrs().get("resolver")
            );
            assertTrue(relation.attrs().containsKey("http_method"));
            assertTrue(relation.attrs().containsKey("path"));
        }
    }

    @Test
    @DisplayName("경로를 해석하지 못해도 PARTIAL HANDLES_ENDPOINT를 보존한다")
    void preservesUnresolvedPathAsPartialRelation() {
        ObservationFact observation = ObservationFact.builder()
                .kind(ObservationKind.HTTP_ENDPOINT)
                .siteSymbol("method:sample.UserController#search()")
                .evidenceIds(List.of("ev-search"))
                .origin(FactOriginKind.AST)
                .confidenceHint(0.9)
                .attrs(Map.of(
                        "http_methods", List.of("GET"),
                        "paths", List.of(),
                        "path_resolution", "unresolved",
                        "method_mapping_attributes", Map.of(
                                "value", "ApiPaths.SEARCH"
                        )
                ))
                .build();

        ObservationResolutionResult result = resolver.resolve(
                contextOf(observation)
        );

        assertEquals(1, result.relations().size());

        RelationFact relation = result.relations().get(0);
        assertEquals("GET <unresolved-path>", relation.dstRawRef());
        assertEquals(
                ResolutionStatus.PARTIAL,
                relation.resolution().status()
        );
        assertEquals(
                "HTTP endpoint path could not be resolved",
                relation.resolution().reason()
        );
        assertEquals(0.6, relation.confidenceHint());
    }

    @Test
    @DisplayName("siteSymbol이 없는 endpoint observation은 경고 후 건너뛴다")
    void skipsObservationWithoutSiteSymbol() {
        ObservationFact observation = ObservationFact.builder()
                .kind(ObservationKind.HTTP_ENDPOINT)
                .attrs(Map.of(
                        "http_methods", List.of("GET"),
                        "paths", List.of("/users")
                ))
                .build();

        ObservationResolutionResult result = resolver.resolve(
                contextOf(observation)
        );

        assertTrue(result.relations().isEmpty());
        assertFalse(result.warnings().isEmpty());
        assertTrue(
                result.warnings().get(0).contains("siteSymbol")
        );
    }

    private ObservationResolutionContext contextOf(
            ObservationFact... observations
    ) {
        ExtractionAggregate aggregate = ExtractionAggregate.builder()
                .observations(ObservationTable.builder()
                        .httpEndpoints(List.of(observations))
                        .build())
                .build();

        return ObservationResolutionContext.from(aggregate);
    }
}
