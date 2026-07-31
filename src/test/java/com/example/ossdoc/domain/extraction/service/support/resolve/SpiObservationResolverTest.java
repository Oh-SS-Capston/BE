package com.example.ossdoc.domain.extraction.service.support.resolve;

import com.example.ossdoc.domain.extraction.dto.model.ExtractionAggregate;
import com.example.ossdoc.domain.extraction.dto.model.ObservationFact;
import com.example.ossdoc.domain.extraction.dto.model.ObservationTable;
import com.example.ossdoc.domain.extraction.dto.model.RelationFact;
import com.example.ossdoc.domain.extraction.dto.model.TypeRef;
import com.example.ossdoc.domain.extraction.enums.DerivationKind;
import com.example.ossdoc.domain.extraction.enums.FactOriginKind;
import com.example.ossdoc.domain.extraction.enums.ObservationKind;
import com.example.ossdoc.domain.extraction.enums.RelationKind;
import com.example.ossdoc.domain.extraction.enums.ResolutionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpiObservationResolverTest {

    private final SpiObservationResolver resolver = new SpiObservationResolver();

    @Test
    @DisplayName("ServiceLoader와 module uses를 LOADS_SERVICE로, module provides를 PROVIDES_SPI로 생성한다")
    void resolvesSpiRelations() {
        ObservationFact serviceLoader = ObservationFact.builder()
                .kind(ObservationKind.SPI_PROVIDER)
                .siteSymbol("method:sample.PluginRegistry#loadPlugins()")
                .targetSymbol("sample.Plugin")
                .evidenceIds(List.of("ev-loader"))
                .origin(FactOriginKind.AST)
                .confidenceHint(0.9)
                .build();

        ObservationFact moduleUses = ObservationFact.builder()
                .kind(ObservationKind.MODULE_USES)
                .siteSymbol("module:sample.app")
                .targetSymbol("sample.Plugin")
                .evidenceIds(List.of("ev-module-uses"))
                .origin(FactOriginKind.AST)
                .confidenceHint(0.9)
                .build();

        ObservationFact moduleProvides = ObservationFact.builder()
                .kind(ObservationKind.MODULE_PROVIDES)
                .siteSymbol("module:sample.plugin")
                .targetSymbol("sample.Plugin")
                .evidenceIds(List.of("ev-module-provides"))
                .origin(FactOriginKind.AST)
                .confidenceHint(0.9)
                .attrs(Map.of("implementation", "sample.DefaultPlugin"))
                .build();

        ObservationResolutionResult result = resolver.resolve(
                contextOf(serviceLoader, moduleUses, moduleProvides)
        );

        assertTrue(result.warnings().isEmpty());
        assertEquals(3, result.relations().size());

        List<RelationFact> loads = result.relations().stream()
                .filter(relation -> relation.kind() == RelationKind.LOADS_SERVICE)
                .toList();
        assertEquals(2, loads.size());
        assertTrue(loads.stream().allMatch(relation ->
                "type:sample.Plugin".equals(relation.dstSymbol())
        ));
        assertTrue(loads.stream().allMatch(relation ->
                relation.resolution().status() == ResolutionStatus.RESOLVED
        ));

        RelationFact provides = result.relations().stream()
                .filter(relation -> relation.kind() == RelationKind.PROVIDES_SPI)
                .findFirst()
                .orElseThrow();
        assertEquals("type:sample.DefaultPlugin", provides.srcSymbol());
        assertEquals("type:sample.Plugin", provides.dstSymbol());
        assertEquals(DerivationKind.DERIVED, provides.derivation());
        assertEquals("module:sample.plugin", provides.attrs().get("module_symbol"));
    }

    @Test
    @DisplayName("ServiceLoader 대상 타입이 unresolved면 PARTIAL LOADS_SERVICE로 남긴다")
    void keepsUnresolvedServiceAsPartialRelation() {
        ObservationFact serviceLoader = ObservationFact.builder()
                .kind(ObservationKind.SPI_PROVIDER)
                .siteSymbol("method:sample.PluginRegistry#loadPlugins()")
                .targetTypeRef(TypeRef.builder()
                        .raw("pluginClassExpression")
                        .sourceText("pluginClassExpression")
                        .unresolved(true)
                        .build())
                .build();

        ObservationResolutionResult result = resolver.resolve(
                contextOf(serviceLoader, null, null)
        );

        RelationFact relation = result.relations().get(0);
        assertEquals(RelationKind.LOADS_SERVICE, relation.kind());
        assertEquals(ResolutionStatus.PARTIAL, relation.resolution().status());
        assertEquals("service:pluginClassExpression", relation.dstRawRef());
    }

    private ObservationResolutionContext contextOf(
            ObservationFact serviceLoader,
            ObservationFact moduleUses,
            ObservationFact moduleProvides
    ) {
        return ObservationResolutionContext.from(
                ExtractionAggregate.builder()
                        .observations(ObservationTable.builder()
                                .spiProviders(serviceLoader == null
                                        ? List.of()
                                        : List.of(serviceLoader))
                                .moduleUses(moduleUses == null
                                        ? List.of()
                                        : List.of(moduleUses))
                                .moduleProvides(moduleProvides == null
                                        ? List.of()
                                        : List.of(moduleProvides))
                                .build())
                        .build()
        );
    }
}
