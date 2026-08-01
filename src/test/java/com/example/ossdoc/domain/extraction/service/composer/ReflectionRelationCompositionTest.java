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
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReflectionRelationCompositionTest {

    @Test
    @DisplayName("Reflection 관계 네 종류를 최종 relations JSON과 stats에 반영한다")
    void composesReflectionRelations() {
        List<ObservationFact> observations = List.of(
                reflection("loadType", Map.of(
                        "api_method", "forName",
                        "reflection_kind", "type",
                        "target_type", "sample.Target"
                )),
                reflection("loadMethod", Map.of(
                        "api_method", "getDeclaredMethod",
                        "reflection_kind", "method",
                        "target_type", "sample.Target",
                        "member_name", "run"
                )),
                reflection("loadField", Map.of(
                        "api_method", "getDeclaredField",
                        "reflection_kind", "field",
                        "target_type", "sample.Target",
                        "member_name", "value"
                )),
                reflection("loadConstructor", Map.of(
                        "api_method", "getDeclaredConstructor",
                        "reflection_kind", "constructor",
                        "target_type", "sample.Target"
                ))
        );

        ExtractionAggregate aggregate = ExtractionAggregate.builder()
                .symbols(symbols())
                .observations(ObservationTable.builder()
                        .reflectionSites(observations)
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
        assertEquals(1, document.relations().reflectsField().size());
        assertEquals(1, document.relations().reflectsConstructor().size());
        assertEquals(4, document.stats().relations());
        assertEquals(4, document.stats().observations());
        assertTrue(document.extraction().warnings().isEmpty());

        JsonNode json = new ObjectMapper()
                .findAndRegisterModules()
                .valueToTree(document);
        assertEquals(1, json.path("relations").path("reflects_type").size());
        assertEquals(1, json.path("relations").path("reflects_method").size());
        assertEquals(1, json.path("relations").path("reflects_field").size());
        assertEquals(1, json.path("relations").path("reflects_constructor").size());
    }

    private ObservationFact reflection(
            String sourceMethod,
            Map<String, Object> attrs
    ) {
        return ObservationFact.builder()
                .kind(ObservationKind.REFLECTION_SITE)
                .siteSymbol("method:sample.Client#" + sourceMethod + "()")
                .origin(FactOriginKind.AST)
                .attrs(attrs)
                .build();
    }

    private SymbolTable symbols() {
        return SymbolTable.builder()
                .types(List.of(symbol(
                        "type:sample.Target",
                        SymbolKind.TYPE,
                        "Target",
                        null,
                        "sample.Target"
                )))
                .methods(List.of(symbol(
                        "method:sample.Target#run()",
                        SymbolKind.METHOD,
                        "run",
                        "type:sample.Target",
                        null
                )))
                .fields(List.of(symbol(
                        "field:sample.Target#value",
                        SymbolKind.FIELD,
                        "value",
                        "type:sample.Target",
                        null
                )))
                .constructors(List.of(symbol(
                        "ctor:sample.Target()",
                        SymbolKind.CONSTRUCTOR,
                        "Target",
                        "type:sample.Target",
                        null
                )))
                .build();
    }

    private SymbolFact symbol(
            String symbol,
            SymbolKind kind,
            String name,
            String owner,
            String qualifiedName
    ) {
        return SymbolFact.builder()
                .symbol(symbol)
                .kind(kind)
                .name(name)
                .ownerSymbol(owner)
                .qualifiedName(qualifiedName)
                .build();
    }
}
