package com.example.ossdoc.domain.extraction.service.composer;
import com.example.ossdoc.domain.extraction.dto.context.FactsCompositionContext;

import com.example.ossdoc.domain.extraction.dto.model.EvidenceFact;
import com.example.ossdoc.domain.extraction.dto.model.ExtractionAggregate;
import com.example.ossdoc.domain.extraction.dto.model.ExtractionMeta;
import com.example.ossdoc.domain.extraction.dto.model.FactsDocument;
import com.example.ossdoc.domain.extraction.dto.model.ObservationTable;
import com.example.ossdoc.domain.extraction.dto.model.RelationTable;
import com.example.ossdoc.domain.extraction.dto.model.StatsMeta;
import com.example.ossdoc.domain.extraction.dto.model.SymbolTable;
import com.example.ossdoc.domain.extraction.service.support.resolve.ObservationRelationResolutionService;
import com.example.ossdoc.domain.extraction.service.support.resolve.ObservationResolutionResult;
import com.example.ossdoc.domain.extraction.service.support.util.FactsSchema;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * merge가 끝난 aggregate를 facts.json 루트 구조로 조립하는 기본 구현.
 *
 * 보장 목표:
 * - schema_version / job / build / extraction / stats / evidence / symbols / relations / observations 섹션이 항상 존재
 * - 중복 symbol/relation/observation/evidence를 composer 단계에서 한 번 더 정리
 * - stats가 최종 문서 내용과 어긋나지 않도록 재계산
 */
@Component
public class DefaultFactsComposer implements FactsComposer {

    private final FactsSectionFactory sectionFactory;
    private final FactsStatsCalculator statsCalculator;
    private final ObservationRelationResolutionService observationResolutionService;

    public DefaultFactsComposer() {
        this(new FactsSectionFactory(), new FactsStatsCalculator(), null);
    }

    /**
     * 기존 단위 테스트와 수동 생성 코드의 호환성을 유지한다.
     */
    public DefaultFactsComposer(
            FactsSectionFactory sectionFactory,
            FactsStatsCalculator statsCalculator
    ) {
        this(sectionFactory, statsCalculator, null);
    }

    /**
     * Spring 환경에서는 resolver service가 존재할 때만 의미 관계 승격을 활성화한다.
     * 기존 테스트처럼 DefaultFactsComposer만 단독 import한 컨텍스트도 계속 동작한다.
     */
    @Autowired
    public DefaultFactsComposer(
            ObjectProvider<ObservationRelationResolutionService> resolverProvider
    ) {
        this(
                new FactsSectionFactory(),
                new FactsStatsCalculator(),
                resolverProvider == null
                        ? null
                        : resolverProvider.getIfAvailable()
        );
    }

    DefaultFactsComposer(
            FactsSectionFactory sectionFactory,
            FactsStatsCalculator statsCalculator,
            ObservationRelationResolutionService observationResolutionService
    ) {
        this.sectionFactory = Objects.requireNonNull(sectionFactory);
        this.statsCalculator = Objects.requireNonNull(statsCalculator);
        this.observationResolutionService = observationResolutionService;
    }

    @Override
    public FactsDocument compose(FactsCompositionContext context) {
        Objects.requireNonNull(context, "context must not be null");

        ExtractionAggregate aggregate = context.aggregate();

        ObservationResolutionResult resolutionResult = resolveObservations(
                aggregate
        );

        Map<String, EvidenceFact> evidence = sectionFactory.composeEvidence(aggregate.evidence());
        SymbolTable symbols = sectionFactory.composeSymbols(aggregate.symbols(), evidence);
        RelationTable relations = sectionFactory.composeRelations(
                aggregate.relations(),
                resolutionResult.relations()
        );
        ObservationTable observations = context.includeObservations()
                ? sectionFactory.composeObservations(aggregate.observations())
                : sectionFactory.emptyObservationTable();

        List<String> warnings = mergeWarnings(
                context.warnings(),
                aggregate.warnings(),
                resolutionResult.warnings()
        );

        ExtractionMeta extractionMeta = ExtractionMeta.builder()
                .mode(context.mode() == null ? null : context.mode().outputCode())
                .startedAt(context.startedAt())
                .finishedAt(context.finishedAt())
                .warnings(warnings)
                .build();

        StatsMeta stats = statsCalculator.compose(
                aggregate.stats(),
                evidence,
                symbols,
                relations,
                observations
        );

        return FactsDocument.builder()
                .schemaVersion(resolveSchemaVersion(context.schemaVersion()))
                .job(sectionFactory.normalizeJob(context.job()))
                .build(sectionFactory.normalizeBuild(context.build()))
                .extraction(extractionMeta)
                .stats(stats)
                .evidenceMap(evidence)
                .symbols(symbols)
                .relations(relations)
                .observations(observations)
                .build();
    }

    private String resolveSchemaVersion(String schemaVersion) {
        if (schemaVersion == null || schemaVersion.isBlank()) {
            return FactsSchema.SCHEMA_VERSION;
        }
        return schemaVersion;
    }

    /**
     * includeObservations는 원시 observation의 JSON 노출 여부만 제어한다.
     * 의미 관계 생성은 원시 observation 노출 여부와 무관하게 수행해야 한다.
     */
    private ObservationResolutionResult resolveObservations(
            ExtractionAggregate aggregate
    ) {
        if (observationResolutionService == null) {
            return ObservationResolutionResult.empty();
        }

        return observationResolutionService.resolve(aggregate);
    }

    @SafeVarargs
    private final List<String> mergeWarnings(List<String>... sources) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (sources != null) {
            for (List<String> source : sources) {
                addWarnings(merged, source);
            }
        }
        return List.copyOf(new ArrayList<>(merged));
    }

    private void addWarnings(LinkedHashSet<String> target, List<String> warnings) {
        if (warnings == null || warnings.isEmpty()) {
            return;
        }
        for (String warning : warnings) {
            if (warning == null || warning.isBlank()) {
                continue;
            }
            target.add(warning);
        }
    }
}
