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
 * file 실행 통계는 aggregate에서 받은 값을 유지하고,
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

        long relationCount = size(relations.calls())
                + size(relations.overrides())
                + size(relations.accessesField());

        long observationCount = size(observations.diInjectionSites())
                + size(observations.diProviders())
                + size(observations.spiProviders())
                + size(observations.eventPublications())
                + size(observations.eventSubscriptions())
                + size(observations.reflectionSites())
                + size(observations.httpEndpoints())
                + size(observations.scheduling())
                + size(observations.asyncMethods())
                + size(observations.configWiring());

        long unresolvedTotal = base.unresolvedTypeRefsTotal();
        long totalTypeRefs = base.totalTypeRefsTotal();
        double ratio = totalTypeRefs > 0 ? (double) unresolvedTotal / totalTypeRefs : 0.0;

        return StatsMeta.builder()
                .filesScanned(base.filesScanned())
                .filesParsed(base.filesParsed())
                .filesSkipped(base.filesSkipped())
                .types(size(symbols.types()))
                .constructors(size(symbols.constructors()))
                .methods(size(symbols.methods()))
                .fields(size(symbols.fields()))
                .relations(relationCount)
                .observations(observationCount)
                .evidence(evidence == null ? 0L : evidence.size())
                .unresolvedTypeRatio(ratio)
                .unresolvedTypeRefsTotal(unresolvedTotal)
                .totalTypeRefsTotal(totalTypeRefs)
                .errors(base.errors())
                .build();
    }

    private long size(List<?> list) {
        return list == null ? 0L : list.size();
    }
}
