package com.example.ossdoc.domain.extraction.service.composer;

import com.example.ossdoc.domain.extraction.dto.model.BuildMeta;
import com.example.ossdoc.domain.extraction.dto.model.ExtractionAggregate;
import com.example.ossdoc.domain.extraction.dto.model.JobMeta;
import com.example.ossdoc.domain.extraction.dto.model.ObservationTable;
import com.example.ossdoc.domain.extraction.dto.model.RelationTable;
import com.example.ossdoc.domain.extraction.dto.model.StatsMeta;
import com.example.ossdoc.domain.extraction.dto.model.SymbolTable;
import com.example.ossdoc.domain.extraction.enums.BytecodeAvailability;
import com.example.ossdoc.domain.extraction.enums.ExtractionMode;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * composer가 FactsDocument를 만들기 위해 필요한 상위 컨텍스트.
 *
 * extractor/merge 단계는 이미 끝났고,
 * composer는 facade/service가 넘겨준 aggregate를 facts.json 형태로만 정리한다.
 */
public record FactsCompositionContext(
        String schemaVersion,
        JobMeta job,
        BuildMeta build,
        ExtractionMode mode,
        BytecodeAvailability bytecodeAvailability,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        Map<String, String> engineVersions,
        List<String> warnings,
        List<String> scannedModules,
        List<String> scannedSourceRoots,
        List<String> scannedBytecodeRoots,
        boolean includeObservations,
        ExtractionAggregate aggregate
) {
    public FactsCompositionContext {
        engineVersions = engineVersions == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(engineVersions));
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        scannedModules = scannedModules == null ? List.of() : List.copyOf(scannedModules);
        scannedSourceRoots = scannedSourceRoots == null ? List.of() : List.copyOf(scannedSourceRoots);
        scannedBytecodeRoots = scannedBytecodeRoots == null ? List.of() : List.copyOf(scannedBytecodeRoots);
        aggregate = aggregate == null ? emptyAggregate() : aggregate;
    }

    private static ExtractionAggregate emptyAggregate() {
        return ExtractionAggregate.builder()
                .evidence(Map.of())
                .symbols(SymbolTable.builder()
                        .modules(List.of())
                        .packages(List.of())
                        .types(List.of())
                        .constructors(List.of())
                        .methods(List.of())
                        .fields(List.of())
                        .build())
                .relations(RelationTable.builder()
                        .contains(List.of())
                        .extendsRelations(List.of())
                        .implementsRelations(List.of())
                        .overrides(List.of())
                        .calls(List.of())
                        .accessesField(List.of())
                        .fieldType(List.of())
                        .paramType(List.of())
                        .returnType(List.of())
                        .throwsType(List.of())
                        .annotatedBy(List.of())
                        .build())
                .observations(ObservationTable.builder()
                        .diInjectionSites(List.of())
                        .diProviders(List.of())
                        .spiProviders(List.of())
                        .eventPublish(List.of())
                        .eventSubscribe(List.of())
                        .reflectionUses(List.of())
                        .configWiring(List.of())
                        .build())
                .stats(StatsMeta.builder().build())
                .warnings(List.of())
                .build();
    }
}
