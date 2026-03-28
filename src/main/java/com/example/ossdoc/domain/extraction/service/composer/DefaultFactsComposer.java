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
import com.example.ossdoc.domain.extraction.service.support.util.FactsSchema;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

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

    public DefaultFactsComposer() {
        this(new FactsSectionFactory(), new FactsStatsCalculator());
    }

    public DefaultFactsComposer(FactsSectionFactory sectionFactory, FactsStatsCalculator statsCalculator) {
        this.sectionFactory = Objects.requireNonNull(sectionFactory);
        this.statsCalculator = Objects.requireNonNull(statsCalculator);
    }

    @Override
    public FactsDocument compose(FactsCompositionContext context) {
        Objects.requireNonNull(context, "context must not be null");

        ExtractionAggregate aggregate = context.aggregate();

        Map<String, EvidenceFact> evidence = sectionFactory.composeEvidence(aggregate.evidence());
        SymbolTable symbols = sectionFactory.composeSymbols(aggregate.symbols());
        RelationTable relations = sectionFactory.composeRelations(aggregate.relations());
        ObservationTable observations = context.includeObservations()
                ? sectionFactory.composeObservations(aggregate.observations())
                : sectionFactory.emptyObservationTable();

        List<String> warnings = mergeWarnings(context.warnings(), aggregate.warnings());

        ExtractionMeta extractionMeta = ExtractionMeta.builder()
                .mode(context.mode())
                .bytecodeAvailability(context.bytecodeAvailability())
                .startedAt(context.startedAt())
                .finishedAt(context.finishedAt())
                .warnings(warnings)
                .scannedModules(context.scannedModules())
                .scannedSourceRoots(context.scannedSourceRoots())
                .scannedBytecodeRoots(context.scannedBytecodeRoots())
                .engineVersions(normalizeEngineVersions(context.engineVersions()))
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
                .evidence(evidence)
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

    private Map<String, String> normalizeEngineVersions(Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }

        Map<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            if (entry.getValue() == null || entry.getValue().isBlank()) {
                continue;
            }
            sorted.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    private List<String> mergeWarnings(List<String> left, List<String> right) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        addWarnings(merged, left);
        addWarnings(merged, right);
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
