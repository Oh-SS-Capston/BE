package com.example.ossdoc.domain.extraction.service.composer;

import com.example.ossdoc.domain.extraction.dto.context.FactsCompositionContext;
import com.example.ossdoc.domain.extraction.dto.model.BuildMeta;
import com.example.ossdoc.domain.extraction.dto.model.ExtractionAggregate;
import com.example.ossdoc.domain.extraction.dto.model.FactsDocument;
import com.example.ossdoc.domain.extraction.dto.model.JobMeta;
import com.example.ossdoc.domain.extraction.dto.model.ObservationFact;
import com.example.ossdoc.domain.extraction.dto.model.ObservationTable;
import com.example.ossdoc.domain.extraction.dto.model.StatsMeta;
import com.example.ossdoc.domain.extraction.dto.model.SymbolFact;
import com.example.ossdoc.domain.extraction.dto.model.SymbolTable;
import com.example.ossdoc.domain.extraction.enums.ExtractionMode;
import com.example.ossdoc.domain.extraction.enums.FactOriginKind;
import com.example.ossdoc.domain.extraction.enums.ObservationKind;
import com.example.ossdoc.domain.extraction.enums.ResolutionStatus;
import com.example.ossdoc.domain.extraction.enums.SymbolKind;
import com.example.ossdoc.domain.extraction.service.support.resolve.ObservationRelationResolutionService;
import com.example.ossdoc.domain.extraction.service.support.resolve.ReflectionObservationResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReflectionPolicyCompositionTest {

    @Test
    @DisplayName("Reflection 공통 정책 메타데이터를 최종 facts.json에 보존한다")
    void composesReflectionPolicyMetadata() {
        ObservationFact resolved = ObservationFact.builder()
                .kind(ObservationKind.REFLECTION_SITE)
                .siteSymbol("method:sample.Client#loadType()")
                .evidenceIds(List.of("ev-type"))
                .origin(FactOriginKind.AST)
                .confidenceHint(0.9)
                .attrs(Map.of(
                        "api_method", "forName",
                        "reflection_kind", "type",
                        "target_type", "sample.Target"
                ))
                .build();

        ObservationFact unresolved = ObservationFact.builder()
                .kind(ObservationKind.REFLECTION_SITE)
                .siteSymbol("method:sample.Client#invoke()")
                .evidenceIds(List.of("ev-invoke"))
                .origin(FactOriginKind.AST)
                .confidenceHint(0.4)
                .attrs(Map.of(
                        "api_method", "invoke",
                        "reflection_kind", "method"
                ))
                .build();

        SymbolFact target = SymbolFact.builder()
                .symbol("type:sample.Target")
                .kind(SymbolKind.TYPE)
                .name("Target")
                .qualifiedName("sample.Target")
                .build();

        ExtractionAggregate aggregate = ExtractionAggregate.builder()
                .symbols(SymbolTable.builder()
                        .types(List.of(target))
                        .constructors(List.of())
                        .methods(List.of())
                        .fields(List.of())
                        .build())
                .observations(ObservationTable.builder()
                        .reflectionSites(List.of(resolved, unresolved))
                        .build())
                .stats(StatsMeta.builder().build())
                .warnings(List.of())
                .build();

        ObservationRelationResolutionService service =
                new ObservationRelationResolutionService(
                        List.of(new ReflectionObservationResolver())
                );
        DefaultFactsComposer composer = new DefaultFactsComposer(
                new FactsSectionFactory(),
                new FactsStatsCalculator(),
                service
        );

        OffsetDateTime now = OffsetDateTime.now();
        FactsDocument document = composer.compose(new FactsCompositionContext(
                "test-schema",
                JobMeta.builder().build(),
                BuildMeta.builder().build(),
                ExtractionMode.AST_ONLY,
                now,
                now,
                List.of(),
                true,
                aggregate
        ));

        assertEquals(1, document.relations().reflectsType().size());
        assertEquals(1, document.relations().reflectsMethod().size());
        assertEquals(2, document.stats().relations());
        assertEquals(2, document.stats().observations());
        assertTrue(document.extraction().warnings().isEmpty());

        var resolvedRelation = document.relations().reflectsType().get(0);
        assertEquals(
                ResolutionStatus.RESOLVED,
                resolvedRelation.resolution().status()
        );
        assertEquals(
                "exact_symbol",
                resolvedRelation.attrs().get("resolution_basis")
        );
        assertEquals(true, resolvedRelation.attrs().get("default_visible"));

        var unresolvedRelation = document.relations().reflectsMethod().get(0);
        assertEquals(
                ResolutionStatus.UNRESOLVED,
                unresolvedRelation.resolution().status()
        );
        assertEquals(
                "unknown_target",
                unresolvedRelation.attrs().get("resolution_basis")
        );
        assertFalse((Boolean) unresolvedRelation.attrs().get("default_visible"));

        JsonNode json = new ObjectMapper()
                .findAndRegisterModules()
                .valueToTree(document);
        JsonNode resolvedJson = json.path("relations")
                .path("reflects_type")
                .get(0)
                .path("attrs");
        assertEquals("exact_symbol", resolvedJson.path("resolution_basis").asText());
        assertEquals("high", resolvedJson.path("confidence_band").asText());
        assertTrue(resolvedJson.path("default_visible").asBoolean());

        JsonNode unresolvedJson = json.path("relations")
                .path("reflects_method")
                .get(0)
                .path("attrs");
        assertEquals("unknown_target", unresolvedJson.path("resolution_basis").asText());
        assertFalse(unresolvedJson.path("default_visible").asBoolean());
    }
}
