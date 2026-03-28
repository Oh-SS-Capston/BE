package com.example.ossdoc.domain.extraction.dto.context;

import com.example.ossdoc.domain.extraction.dto.model.ChunkDescriptor;
import com.example.ossdoc.domain.extraction.dto.model.ChunkResult;
import com.example.ossdoc.domain.extraction.dto.model.EvidenceFact;
import com.example.ossdoc.domain.extraction.dto.model.ObservationFact;
import com.example.ossdoc.domain.extraction.dto.model.ObservationTable;
import com.example.ossdoc.domain.extraction.dto.model.RelationFact;
import com.example.ossdoc.domain.extraction.dto.model.RelationTable;
import com.example.ossdoc.domain.extraction.dto.model.StatsMeta;
import com.example.ossdoc.domain.extraction.dto.model.SymbolFact;
import com.example.ossdoc.domain.extraction.dto.model.SymbolTable;
import com.example.ossdoc.domain.extraction.enums.ChunkStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * extractor 내부에서만 쓰는 중간 집계 객체.
 *
 * 새 파이프라인의 기본 반환형은 ChunkResult지만,
 * 테스트/로컬 유틸에서 aggregate 자체가 필요할 수 있어 보조로 유지한다.
 */
public record ExtractedFacts(
        Map<String, EvidenceFact> evidence,
        SymbolTable symbols,
        RelationTable relations,
        ObservationTable observations,
        StatsMeta stats,
        List<String> warnings,
        List<String> errors
) {

    public ExtractedFacts {
        evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public static ExtractedFacts empty() {
        return new ExtractedFacts(
                Map.of(),
                SymbolTable.builder()
                        .modules(List.of())
                        .packages(List.of())
                        .types(List.of())
                        .constructors(List.of())
                        .methods(List.of())
                        .fields(List.of())
                        .build(),
                RelationTable.builder()
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
                        .build(),
                ObservationTable.builder()
                        .diInjectionSites(List.of())
                        .diProviders(List.of())
                        .spiProviders(List.of())
                        .eventPublish(List.of())
                        .eventSubscribe(List.of())
                        .reflectionUses(List.of())
                        .configWiring(List.of())
                        .build(),
                StatsMeta.builder().build(),
                List.of(),
                List.of()
        );
    }

    public ChunkResult toChunkResult(ChunkDescriptor descriptor, ChunkStatus status) {
        return ChunkResult.builder()
                .descriptor(descriptor)
                .status(status)
                .evidence(evidence)
                .symbols(mergeSymbolLists())
                .relations(mergeRelationLists())
                .observations(mergeObservationLists())
                .stats(stats)
                .warnings(warnings)
                .errors(errors)
                .build();
    }

    public ExtractedFacts merge(ExtractedFacts other) {
        if (other == null) {
            return this;
        }

        Map<String, EvidenceFact> mergedEvidence = new LinkedHashMap<>();
        mergedEvidence.putAll(this.evidence);
        mergedEvidence.putAll(other.evidence);

        return new ExtractedFacts(
                mergedEvidence,
                SymbolTable.builder()
                        .modules(mergeLists(this.symbols.modules(), other.symbols.modules()))
                        .packages(mergeLists(this.symbols.packages(), other.symbols.packages()))
                        .types(mergeLists(this.symbols.types(), other.symbols.types()))
                        .constructors(mergeLists(this.symbols.constructors(), other.symbols.constructors()))
                        .methods(mergeLists(this.symbols.methods(), other.symbols.methods()))
                        .fields(mergeLists(this.symbols.fields(), other.symbols.fields()))
                        .build(),
                RelationTable.builder()
                        .contains(mergeLists(this.relations.contains(), other.relations.contains()))
                        .extendsRelations(mergeLists(this.relations.extendsRelations(), other.relations.extendsRelations()))
                        .implementsRelations(mergeLists(this.relations.implementsRelations(), other.relations.implementsRelations()))
                        .overrides(mergeLists(this.relations.overrides(), other.relations.overrides()))
                        .calls(mergeLists(this.relations.calls(), other.relations.calls()))
                        .accessesField(mergeLists(this.relations.accessesField(), other.relations.accessesField()))
                        .fieldType(mergeLists(this.relations.fieldType(), other.relations.fieldType()))
                        .paramType(mergeLists(this.relations.paramType(), other.relations.paramType()))
                        .returnType(mergeLists(this.relations.returnType(), other.relations.returnType()))
                        .throwsType(mergeLists(this.relations.throwsType(), other.relations.throwsType()))
                        .annotatedBy(mergeLists(this.relations.annotatedBy(), other.relations.annotatedBy()))
                        .build(),
                ObservationTable.builder()
                        .diInjectionSites(mergeLists(this.observations.diInjectionSites(), other.observations.diInjectionSites()))
                        .diProviders(mergeLists(this.observations.diProviders(), other.observations.diProviders()))
                        .spiProviders(mergeLists(this.observations.spiProviders(), other.observations.spiProviders()))
                        .eventPublish(mergeLists(this.observations.eventPublish(), other.observations.eventPublish()))
                        .eventSubscribe(mergeLists(this.observations.eventSubscribe(), other.observations.eventSubscribe()))
                        .reflectionUses(mergeLists(this.observations.reflectionUses(), other.observations.reflectionUses()))
                        .configWiring(mergeLists(this.observations.configWiring(), other.observations.configWiring()))
                        .build(),
                StatsMeta.builder()
                        .filesScanned(this.stats.filesScanned() + other.stats.filesScanned())
                        .filesParsed(this.stats.filesParsed() + other.stats.filesParsed())
                        .filesSkipped(this.stats.filesSkipped() + other.stats.filesSkipped())
                        .astFilesScanned(this.stats.astFilesScanned() + other.stats.astFilesScanned())
                        .classFilesScanned(this.stats.classFilesScanned() + other.stats.classFilesScanned())
                        .astFilesParsed(this.stats.astFilesParsed() + other.stats.astFilesParsed())
                        .classFilesParsed(this.stats.classFilesParsed() + other.stats.classFilesParsed())
                        .chunksTotal(this.stats.chunksTotal() + other.stats.chunksTotal())
                        .chunksSucceeded(this.stats.chunksSucceeded() + other.stats.chunksSucceeded())
                        .chunksFailed(this.stats.chunksFailed() + other.stats.chunksFailed())
                        .chunksPartial(this.stats.chunksPartial() + other.stats.chunksPartial())
                        .types(this.stats.types() + other.stats.types())
                        .constructors(this.stats.constructors() + other.stats.constructors())
                        .methods(this.stats.methods() + other.stats.methods())
                        .fields(this.stats.fields() + other.stats.fields())
                        .edgeCandidates(this.stats.edgeCandidates() + other.stats.edgeCandidates())
                        .relations(this.stats.relations() + other.stats.relations())
                        .observations(this.stats.observations() + other.stats.observations())
                        .evidence(this.stats.evidence() + other.stats.evidence())
                        .unresolvedTypeRefs(this.stats.unresolvedTypeRefs() + other.stats.unresolvedTypeRefs())
                        .errors(this.stats.errors() + other.stats.errors())
                        .build(),
                mergeLists(this.warnings, other.warnings),
                mergeLists(this.errors, other.errors)
        );
    }

    private List<SymbolFact> mergeSymbolLists() {
        List<SymbolFact> merged = new ArrayList<>();
        merged.addAll(symbols.modules());
        merged.addAll(symbols.packages());
        merged.addAll(symbols.types());
        merged.addAll(symbols.constructors());
        merged.addAll(symbols.methods());
        merged.addAll(symbols.fields());
        return List.copyOf(merged);
    }

    private List<RelationFact> mergeRelationLists() {
        List<RelationFact> merged = new ArrayList<>();
        merged.addAll(relations.contains());
        merged.addAll(relations.extendsRelations());
        merged.addAll(relations.implementsRelations());
        merged.addAll(relations.overrides());
        merged.addAll(relations.calls());
        merged.addAll(relations.accessesField());
        merged.addAll(relations.fieldType());
        merged.addAll(relations.paramType());
        merged.addAll(relations.returnType());
        merged.addAll(relations.throwsType());
        merged.addAll(relations.annotatedBy());
        return List.copyOf(merged);
    }

    private List<ObservationFact> mergeObservationLists() {
        List<ObservationFact> merged = new ArrayList<>();
        merged.addAll(observations.diInjectionSites());
        merged.addAll(observations.diProviders());
        merged.addAll(observations.spiProviders());
        merged.addAll(observations.eventPublish());
        merged.addAll(observations.eventSubscribe());
        merged.addAll(observations.reflectionUses());
        merged.addAll(observations.configWiring());
        return List.copyOf(merged);
    }

    private static <T> List<T> mergeLists(List<T> left, List<T> right) {
        List<T> merged = new ArrayList<>();
        if (left != null) {
            merged.addAll(left);
        }
        if (right != null) {
            merged.addAll(right);
        }
        return List.copyOf(merged);
    }
}
