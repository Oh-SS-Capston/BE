package com.example.ossdoc.domain.extraction.service.composer;

import com.example.ossdoc.domain.extraction.dto.model.EvidenceFact;
import com.example.ossdoc.domain.extraction.dto.model.ObservationTable;
import com.example.ossdoc.domain.extraction.dto.model.RelationTable;
import com.example.ossdoc.domain.extraction.dto.model.StatsMeta;
import com.example.ossdoc.domain.extraction.dto.model.SymbolTable;

import java.util.List;
import java.util.Map;

/**
 * 최종 facts.json에 기록할 stats를 실제 조립 결과 기준으로 계산한다.
 *
 * file/chunk 실행 통계는 aggregate에서 받은 값을 유지하고,
 * symbol/relation/observation/evidence 카운트는 최종 문서 기준으로 다시 맞춘다.
 */
final class FactsStatsCalculator {

    StatsMeta compose(
            StatsMeta rawStats,
            Map<String, EvidenceFact> evidence,
            SymbolTable symbols,
            RelationTable relations,
            ObservationTable observations
    ) {
        StatsMeta base = rawStats == null ? StatsMeta.builder().build() : rawStats;

        long relationCount = size(relations.contains())
                + size(relations.extendsRelations())
                + size(relations.implementsRelations())
                + size(relations.overrides())
                + size(relations.calls())
                + size(relations.accessesField())
                + size(relations.fieldType())
                + size(relations.paramType())
                + size(relations.returnType())
                + size(relations.throwsType())
                + size(relations.annotatedBy());

        long observationCount = size(observations.diInjectionSites())
                + size(observations.diProviders())
                + size(observations.spiProviders())
                + size(observations.eventPublish())
                + size(observations.eventSubscribe())
                + size(observations.reflectionUses())
                + size(observations.configWiring());

        long derivedFilesScanned = base.astFilesScanned() + base.classFilesScanned();
        long derivedFilesParsed = base.astFilesParsed() + base.classFilesParsed();

        return StatsMeta.builder()
                .filesScanned(preferExplicitOrDerived(base.filesScanned(), derivedFilesScanned))
                .filesParsed(preferExplicitOrDerived(base.filesParsed(), derivedFilesParsed))
                .filesSkipped(base.filesSkipped())
                .astFilesScanned(base.astFilesScanned())
                .classFilesScanned(base.classFilesScanned())
                .astFilesParsed(base.astFilesParsed())
                .classFilesParsed(base.classFilesParsed())
                .chunksTotal(base.chunksTotal())
                .chunksSucceeded(base.chunksSucceeded())
                .chunksFailed(base.chunksFailed())
                .chunksPartial(base.chunksPartial())
                .types(size(symbols.types()))
                .constructors(size(symbols.constructors()))
                .methods(size(symbols.methods()))
                .fields(size(symbols.fields()))
                .edgeCandidates(base.edgeCandidates() > 0 ? base.edgeCandidates() : relationCount)
                .relations(relationCount)
                .observations(observationCount)
                .evidence(evidence == null ? 0L : evidence.size())
                .unresolvedTypeRefs(base.unresolvedTypeRefs())
                .errors(base.errors())
                .build();
    }

    private long size(List<?> list) {
        return list == null ? 0L : list.size();
    }

    private long preferExplicitOrDerived(long explicitValue, long derivedValue) {
        return explicitValue > 0 ? explicitValue : derivedValue;
    }
}
