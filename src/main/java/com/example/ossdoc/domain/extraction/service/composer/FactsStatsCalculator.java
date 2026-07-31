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
 * <p>파일 실행 통계는 aggregate에서 받은 값을 유지하고,
 * symbol/relation/observation/evidence 카운트는 최종 문서 기준으로 다시 맞춘다.</p>
 */
final class FactsStatsCalculator {

    StatsMeta compose(
            StatsMeta rawStats,
            Map<String, EvidenceFact> evidence,
            SymbolTable symbols,
            RelationTable relations,
            ObservationTable observations
    ) {
        StatsMeta base = rawStats == null
                ? StatsMeta.builder().build()
                : rawStats;

        long relationCount = size(relations == null ? null : relations.calls())
                + size(relations == null ? null : relations.creates())
                + size(relations == null ? null : relations.overrides())
                + size(relations == null ? null : relations.accessesField())
                + size(relations == null ? null : relations.annotatedWith())
                + size(relations == null ? null : relations.handlesEndpoint())
                + size(relations == null ? null : relations.declaresBean())
                + size(relations == null ? null : relations.configuresBean())
                + size(relations == null ? null : relations.injects())
                + size(relations == null ? null : relations.publishesEvent())
                + size(relations == null ? null : relations.listensEvent())
                + size(relations == null ? null : relations.providesSpi())
                + size(relations == null ? null : relations.loadsService())
                + size(relations == null ? null : relations.reflectsType())
                + size(relations == null ? null : relations.reflectsMethod())
                + size(relations == null ? null : relations.reflectsField())
                + size(relations == null ? null : relations.reflectsConstructor());

        long observationCount = size(observations == null ? null : observations.diInjectionSites())
                + size(observations == null ? null : observations.diProviders())
                + size(observations == null ? null : observations.spiProviders())
                + size(observations == null ? null : observations.eventPublications())
                + size(observations == null ? null : observations.eventSubscriptions())
                + size(observations == null ? null : observations.reflectionSites())
                + size(observations == null ? null : observations.httpEndpoints())
                + size(observations == null ? null : observations.scheduling())
                + size(observations == null ? null : observations.asyncMethods())
                + size(observations == null ? null : observations.configWiring())
                + size(observations == null ? null : observations.readmeMentions())
                + size(observations == null ? null : observations.moduleExports())
                + size(observations == null ? null : observations.moduleUses())
                + size(observations == null ? null : observations.moduleProvides());

        long unresolvedTotal = base.unresolvedTypeRefsTotal();
        long totalTypeRefs = base.totalTypeRefsTotal();
        double ratio = totalTypeRefs > 0
                ? (double) unresolvedTotal / totalTypeRefs
                : 0.0;

        return StatsMeta.builder()
                .filesScanned(base.filesScanned())
                .filesParsed(base.filesParsed())
                .filesSkipped(base.filesSkipped())
                .types(size(symbols == null ? null : symbols.types()))
                .constructors(size(symbols == null ? null : symbols.constructors()))
                .methods(size(symbols == null ? null : symbols.methods()))
                .fields(size(symbols == null ? null : symbols.fields()))
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
