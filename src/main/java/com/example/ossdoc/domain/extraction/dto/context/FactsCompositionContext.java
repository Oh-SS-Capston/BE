package com.example.ossdoc.domain.extraction.dto.context;

import com.example.ossdoc.domain.extraction.dto.model.BuildMeta;
import com.example.ossdoc.domain.extraction.dto.model.ExtractionAggregate;
import com.example.ossdoc.domain.extraction.dto.model.JobMeta;
import com.example.ossdoc.domain.extraction.dto.model.ObservationTable;
import com.example.ossdoc.domain.extraction.dto.model.RelationTable;
import com.example.ossdoc.domain.extraction.dto.model.StatsMeta;
import com.example.ossdoc.domain.extraction.dto.model.SymbolTable;
import com.example.ossdoc.domain.extraction.enums.ExtractionMode;

import java.time.OffsetDateTime;
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
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        List<String> warnings,
        boolean includeObservations,
        ExtractionAggregate aggregate
) {
    public FactsCompositionContext {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        aggregate = aggregate == null ? emptyAggregate() : aggregate;
    }

    private static ExtractionAggregate emptyAggregate() {
        return ExtractionAggregate.builder()
                .evidence(Map.of())
                .symbols(SymbolTable.builder()
                        .types(List.of())
                        .constructors(List.of())
                        .methods(List.of())
                        .fields(List.of())
                        .build())
                .relations(RelationTable.builder()
                        .calls(List.of())
                        .creates(List.of())
                        .overrides(List.of())
                        .accessesField(List.of())
                        .annotatedWith(List.of())
                        .handlesEndpoint(List.of())
                        .declaresBean(List.of())
                        .configuresBean(List.of())
                        .injects(List.of())
                        .publishesEvent(List.of())
                        .listensEvent(List.of())
                        .providesSpi(List.of())
                        .loadsService(List.of())
                        .build())
                .observations(ObservationTable.builder()
                        .diInjectionSites(List.of())
                        .diProviders(List.of())
                        .spiProviders(List.of())
                        .eventPublications(List.of())
                        .eventSubscriptions(List.of())
                        .reflectionSites(List.of())
                        .httpEndpoints(List.of())
                        .scheduling(List.of())
                        .asyncMethods(List.of())
                        .configWiring(List.of())
                        .readmeMentions(List.of())
                        .moduleExports(List.of())
                        .moduleUses(List.of())
                        .moduleProvides(List.of())
                        .build())
                .stats(StatsMeta.builder().build())
                .warnings(List.of())
                .build();
    }
}
