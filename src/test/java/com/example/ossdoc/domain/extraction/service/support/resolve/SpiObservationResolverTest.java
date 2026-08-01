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

    private final SpiObservationResolver resolver =
            new SpiObservationResolver();

    @Test
    @DisplayName("ServiceLoader·module uses·module provides에 공통 정책을 적용한다")
    void resolvesSpiRelationsWithCommonPolicy() {
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
                .filter(relation ->
                        relation.kind() == RelationKind.LOADS_SERVICE)
                .toList();
        assertEquals(2, loads.size());
        assertTrue(loads.stream().allMatch(relation ->
                "type:sample.Plugin".equals(relation.dstSymbol())
        ));
        assertTrue(loads.stream().allMatch(relation ->
                relation.resolution().status() == ResolutionStatus.RESOLVED
        ));
        assertTrue(loads.stream().allMatch(relation ->
                "exact_symbol".equals(
                        relation.attrs().get("resolution_basis")
                )
        ));
        assertTrue(loads.stream().allMatch(relation ->
                "high".equals(relation.attrs().get("confidence_band"))
        ));
        assertTrue(loads.stream().allMatch(relation ->
                Boolean.TRUE.equals(relation.attrs().get("default_visible"))
        ));
        assertTrue(loads.stream().allMatch(relation ->
                Math.abs(relation.confidenceHint() - 0.923) < 0.0001
        ));

        RelationFact provides = result.relations().stream()
                .filter(relation ->
                        relation.kind() == RelationKind.PROVIDES_SPI)
                .findFirst()
                .orElseThrow();
        assertEquals("type:sample.DefaultPlugin", provides.srcSymbol());
        assertEquals("type:sample.Plugin", provides.dstSymbol());
        assertEquals(DerivationKind.DERIVED, provides.derivation());
        assertEquals(
                "module:sample.plugin",
                provides.attrs().get("module_symbol")
        );
        assertEquals("exact_symbol", provides.attrs().get("resolution_basis"));
        assertEquals("high", provides.attrs().get("confidence_band"));
        assertEquals(Boolean.TRUE, provides.attrs().get("default_visible"));
        assertEquals(0.923, provides.confidenceHint(), 0.0001);
    }

    @Test
    @DisplayName("ServiceLoader 대상 문자열만 확인되면 RAW_REFERENCE 기반 PARTIAL로 남긴다")
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
        assertEquals("raw_reference", relation.attrs().get("resolution_basis"));
        assertEquals("medium", relation.attrs().get("confidence_band"));
        assertEquals(Boolean.FALSE, relation.attrs().get("default_visible"));
        assertEquals(0.4, relation.confidenceHint(), 0.0001);
    }

    @Test
    @DisplayName("module provides 구현체가 없으면 module source를 사용하고 INFERRED_SYMBOL로 남긴다")
    void marksMissingImplementationAsInferred() {
        ObservationFact moduleProvides = ObservationFact.builder()
                .kind(ObservationKind.MODULE_PROVIDES)
                .siteSymbol("module:sample.plugin")
                .targetSymbol("sample.Plugin")
                .evidenceIds(List.of("ev-module-provides"))
                .origin(FactOriginKind.AST)
                .confidenceHint(0.9)
                .build();

        RelationFact relation = resolver.resolve(
                contextOf(null, null, moduleProvides)
        ).relations().get(0);

        assertEquals(RelationKind.PROVIDES_SPI, relation.kind());
        assertEquals("module:sample.plugin", relation.srcSymbol());
        assertEquals(ResolutionStatus.PARTIAL, relation.resolution().status());
        assertEquals("inferred_symbol", relation.attrs().get("resolution_basis"));
        assertEquals("medium", relation.attrs().get("confidence_band"));
        assertEquals(Boolean.FALSE, relation.attrs().get("default_visible"));
        assertEquals(0.57, relation.confidenceHint(), 0.0001);
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
