package com.example.ossdoc.domain.extraction.service.support.merge;
import com.example.ossdoc.domain.extraction.service.support.util.WarningCollector;

import com.example.ossdoc.domain.extraction.dto.model.ChunkResult;
import com.example.ossdoc.domain.extraction.dto.model.EvidenceFact;
import com.example.ossdoc.domain.extraction.dto.model.ExtractionAggregate;
import com.example.ossdoc.domain.extraction.dto.model.ModuleMergeResult;
import com.example.ossdoc.domain.extraction.dto.model.ObservationFact;
import com.example.ossdoc.domain.extraction.dto.model.ObservationTable;
import com.example.ossdoc.domain.extraction.dto.model.RelationFact;
import com.example.ossdoc.domain.extraction.dto.model.RelationTable;
import com.example.ossdoc.domain.extraction.dto.model.RootMergeResult;
import com.example.ossdoc.domain.extraction.dto.model.StatsMeta;
import com.example.ossdoc.domain.extraction.dto.model.SymbolFact;
import com.example.ossdoc.domain.extraction.dto.model.SymbolTable;
import com.example.ossdoc.domain.extraction.enums.MergeStage;
import com.example.ossdoc.domain.extraction.enums.ObservationKind;
import com.example.ossdoc.domain.extraction.enums.RelationKind;
import com.example.ossdoc.domain.extraction.enums.SymbolKind;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
public class ExtractionMergeSupport {

    public RootMergeResult mergeRoot(String module, String rootPath, List<ChunkResult> chunkResults) {
        List<ChunkResult> safeResults = chunkResults == null ? List.of() : List.copyOf(chunkResults);
        StatsAccumulator stats = new StatsAccumulator();
        WarningCollector warnings = new WarningCollector();

        Map<String, EvidenceFact> evidence = new LinkedHashMap<>();
        Map<String, SymbolFact> symbols = new LinkedHashMap<>();
        Map<String, RelationFact> relations = new LinkedHashMap<>();
        Map<String, ObservationFact> observations = new LinkedHashMap<>();

        for (ChunkResult chunkResult : safeResults) {
            if (chunkResult == null) {
                continue;
            }

            stats.recordChunkResult(chunkResult);
            warnings.addAll(chunkResult.warnings());
            warnings.addAll(chunkResult.errors());
            mergeEvidence(evidence, chunkResult.evidence());
            mergeSymbols(symbols, chunkResult.symbols(), stats);
            mergeRelations(relations, chunkResult.relations(), stats);
            mergeObservations(observations, chunkResult.observations(), stats);
        }

        return RootMergeResult.builder()
                .module(module)
                .rootPath(rootPath)
                .kind(safeResults.isEmpty() || safeResults.get(0) == null || safeResults.get(0).descriptor() == null
                        ? null
                        : safeResults.get(0).descriptor().kind())
                .stage(MergeStage.ROOT)
                .chunkResults(safeResults)
                .evidence(evidence)
                .symbols(new ArrayList<>(symbols.values()))
                .relations(new ArrayList<>(relations.values()))
                .observations(new ArrayList<>(observations.values()))
                .stats(stats.snapshot())
                .warnings(warnings.snapshot())
                .build();
    }

    public ModuleMergeResult mergeModule(String module, List<RootMergeResult> roots) {
        List<RootMergeResult> safeRoots = roots == null ? List.of() : List.copyOf(roots);
        StatsAccumulator stats = new StatsAccumulator();
        WarningCollector warnings = new WarningCollector();

        Map<String, EvidenceFact> evidence = new LinkedHashMap<>();
        Map<String, SymbolFact> symbols = new LinkedHashMap<>();
        Map<String, RelationFact> relations = new LinkedHashMap<>();
        Map<String, ObservationFact> observations = new LinkedHashMap<>();

        for (RootMergeResult root : safeRoots) {
            if (root == null) {
                continue;
            }
            stats.merge(root.stats());
            warnings.addAll(root.warnings());
            mergeEvidence(evidence, root.evidence());
            mergeSymbols(symbols, root.symbols(), null);
            mergeRelations(relations, root.relations(), null);
            mergeObservations(observations, root.observations(), null);
        }

        return ModuleMergeResult.builder()
                .module(module)
                .stage(MergeStage.MODULE)
                .roots(safeRoots)
                .evidence(evidence)
                .symbols(new ArrayList<>(symbols.values()))
                .relations(new ArrayList<>(relations.values()))
                .observations(new ArrayList<>(observations.values()))
                .stats(stats.snapshot())
                .warnings(warnings.snapshot())
                .build();
    }

    public ExtractionAggregate mergeChunkIntoAggregate(ExtractionAggregate base, ChunkResult extra) {
        if (extra == null) return base;

        Map<String, EvidenceFact> evidence = new LinkedHashMap<>(base.evidence());
        Map<String, SymbolFact> symbols = flattenSymbols(base.symbols());
        Map<String, RelationFact> relations = flattenRelations(base.relations());
        Map<String, ObservationFact> observations = flattenObservations(base.observations());

        mergeEvidence(evidence, extra.evidence());
        mergeSymbols(symbols, extra.symbols(), null);
        mergeRelations(relations, extra.relations(), null);
        mergeObservations(observations, extra.observations(), null);

        return ExtractionAggregate.builder()
                .evidence(evidence)
                .symbols(toSymbolTable(symbols.values()))
                .relations(toRelationTable(relations.values()))
                .observations(toObservationTable(observations.values()))
                .stats(base.stats())
                .warnings(base.warnings())
                .build();
    }

    public ExtractionAggregate aggregate(List<ModuleMergeResult> modules) {
        List<ModuleMergeResult> safeModules = modules == null ? List.of() : List.copyOf(modules);
        StatsAccumulator stats = new StatsAccumulator();
        WarningCollector warnings = new WarningCollector();

        Map<String, EvidenceFact> evidence = new LinkedHashMap<>();
        Map<String, SymbolFact> symbols = new LinkedHashMap<>();
        Map<String, RelationFact> relations = new LinkedHashMap<>();
        Map<String, ObservationFact> observations = new LinkedHashMap<>();

        for (ModuleMergeResult module : safeModules) {
            if (module == null) {
                continue;
            }
            stats.merge(module.stats());
            warnings.addAll(module.warnings());
            mergeEvidence(evidence, module.evidence());
            mergeSymbols(symbols, module.symbols(), null);
            mergeRelations(relations, module.relations(), null);
            mergeObservations(observations, module.observations(), null);
        }

        return ExtractionAggregate.builder()
                .evidence(evidence)
                .symbols(toSymbolTable(symbols.values()))
                .relations(toRelationTable(relations.values()))
                .observations(toObservationTable(observations.values()))
                .stats(stats.snapshot())
                .warnings(warnings.snapshot())
                .build();
    }

    private void mergeEvidence(Map<String, EvidenceFact> target, Map<String, EvidenceFact> source) {
        if (source == null || source.isEmpty()) {
            return;
        }
        for (Map.Entry<String, EvidenceFact> entry : source.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            target.putIfAbsent(entry.getKey(), entry.getValue());
        }
    }

    private void mergeSymbols(Map<String, SymbolFact> target, List<SymbolFact> source, StatsAccumulator stats) {
        if (source == null || source.isEmpty()) {
            return;
        }
        for (SymbolFact symbol : source) {
            if (symbol == null || symbol.symbol() == null || symbol.symbol().isBlank()) {
                continue;
            }
            SymbolFact previous = target.putIfAbsent(symbol.symbol(), symbol);
            if (previous == null && stats != null) {
                stats.recordSymbol(symbol);
            }
        }
    }

    private void mergeRelations(Map<String, RelationFact> target, List<RelationFact> source, StatsAccumulator stats) {
        if (source == null || source.isEmpty()) {
            return;
        }
        for (RelationFact relation : source) {
            if (relation == null) {
                continue;
            }
            String key = relationKey(relation);
            RelationFact previous = target.putIfAbsent(key, relation);
            if (previous == null && stats != null) {
                stats.recordRelation();
            }
        }
    }

    private void mergeObservations(Map<String, ObservationFact> target, List<ObservationFact> source, StatsAccumulator stats) {
        if (source == null || source.isEmpty()) {
            return;
        }
        for (ObservationFact observation : source) {
            if (observation == null) {
                continue;
            }
            String key = observationKey(observation);
            ObservationFact previous = target.putIfAbsent(key, observation);
            if (previous == null && stats != null) {
                stats.recordObservation();
            }
        }
    }

    private String relationKey(RelationFact relation) {
        return String.join("|",
                safeCode(relation.kind()),
                nullToEmpty(relation.srcSymbol()),
                nullToEmpty(relation.dstSymbol()),
                nullToEmpty(relation.dstRawRef()),
                nullToEmpty(relation.origin() == null ? null : relation.origin().code())
        );
    }

    private String observationKey(ObservationFact observation) {
        return String.join("|",
                safeCode(observation.kind()),
                nullToEmpty(observation.siteSymbol()),
                nullToEmpty(observation.targetSymbol()),
                nullToEmpty(observation.note())
        );
    }

    private String safeCode(Object enumValue) {
        return enumValue == null ? "" : enumValue.toString();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private SymbolTable toSymbolTable(Collection<SymbolFact> values) {
        List<SymbolFact> all = values == null ? List.of() : new ArrayList<>(values);
        return SymbolTable.builder()
                .types(filterSymbols(all, SymbolKind.TYPE))
                .constructors(filterSymbols(all, SymbolKind.CONSTRUCTOR))
                .methods(filterSymbols(all, SymbolKind.METHOD))
                .fields(filterSymbols(all, SymbolKind.FIELD))
                .build();
    }

    private RelationTable toRelationTable(Collection<RelationFact> values) {
        List<RelationFact> all = values == null ? List.of() : new ArrayList<>(values);
        return RelationTable.builder()
                .calls(filterRelations(all, RelationKind.CALLS))
                .overrides(filterRelations(all, RelationKind.OVERRIDES))
                .accessesField(filterRelations(all, RelationKind.ACCESSES_FIELD))
                .build();
    }

    private ObservationTable toObservationTable(Collection<ObservationFact> values) {
        List<ObservationFact> all = values == null ? List.of() : new ArrayList<>(values);
        return ObservationTable.builder()
                .diInjectionSites(filterObservations(all, ObservationKind.DI_INJECTION_SITE))
                .diProviders(filterObservations(all, ObservationKind.DI_PROVIDER))
                .spiProviders(filterObservations(all, ObservationKind.SPI_PROVIDER))
                .eventPublications(filterObservations(all, ObservationKind.EVENT_PUBLICATION))
                .eventSubscriptions(filterObservations(all, ObservationKind.EVENT_SUBSCRIPTION))
                .reflectionSites(filterObservations(all, ObservationKind.REFLECTION_SITE))
                .httpEndpoints(filterObservations(all, ObservationKind.HTTP_ENDPOINT))
                .scheduling(filterObservations(all, ObservationKind.SCHEDULED_TASK))
                .asyncMethods(filterObservations(all, ObservationKind.ASYNC_METHOD))
                .configWiring(filterObservations(all, ObservationKind.CONFIG_WIRING))
                .readmeMentions(filterObservations(all, ObservationKind.README_MENTION))
                .moduleExports(filterObservations(all, ObservationKind.MODULE_EXPORTS))
                .moduleUses(filterObservations(all, ObservationKind.MODULE_USES))
                .moduleProvides(filterObservations(all, ObservationKind.MODULE_PROVIDES))
                .build();
    }

    private List<SymbolFact> filterSymbols(List<SymbolFact> values, SymbolKind kind) {
        return values.stream()
                .filter(Objects::nonNull)
                .filter(value -> value.kind() == kind)
                .toList();
    }

    private List<RelationFact> filterRelations(List<RelationFact> values, RelationKind kind) {
        return values.stream()
                .filter(Objects::nonNull)
                .filter(value -> value.kind() == kind)
                .toList();
    }

    private List<ObservationFact> filterObservations(List<ObservationFact> values, ObservationKind kind) {
        return values.stream()
                .filter(Objects::nonNull)
                .filter(value -> value.kind() == kind)
                .toList();
    }

    private Map<String, SymbolFact> flattenSymbols(SymbolTable table) {
        Map<String, SymbolFact> map = new LinkedHashMap<>();
        if (table == null) return map;
        addToSymbolMap(map, table.types());
        addToSymbolMap(map, table.constructors());
        addToSymbolMap(map, table.methods());
        addToSymbolMap(map, table.fields());
        return map;
    }

    private void addToSymbolMap(Map<String, SymbolFact> map, List<SymbolFact> symbols) {
        if (symbols == null) return;
        for (SymbolFact s : symbols) {
            if (s != null && s.symbol() != null && !s.symbol().isBlank()) {
                map.put(s.symbol(), s);
            }
        }
    }

    private Map<String, RelationFact> flattenRelations(RelationTable table) {
        Map<String, RelationFact> map = new LinkedHashMap<>();
        if (table == null) return map;
        addToRelationMap(map, table.calls());
        addToRelationMap(map, table.overrides());
        addToRelationMap(map, table.accessesField());
        return map;
    }

    private void addToRelationMap(Map<String, RelationFact> map, List<RelationFact> relations) {
        if (relations == null) return;
        for (RelationFact r : relations) {
            if (r != null) map.put(relationKey(r), r);
        }
    }

    private Map<String, ObservationFact> flattenObservations(ObservationTable table) {
        Map<String, ObservationFact> map = new LinkedHashMap<>();
        if (table == null) return map;
        addToObservationMap(map, table.diInjectionSites());
        addToObservationMap(map, table.diProviders());
        addToObservationMap(map, table.spiProviders());
        addToObservationMap(map, table.eventPublications());
        addToObservationMap(map, table.eventSubscriptions());
        addToObservationMap(map, table.reflectionSites());
        addToObservationMap(map, table.httpEndpoints());
        addToObservationMap(map, table.scheduling());
        addToObservationMap(map, table.asyncMethods());
        addToObservationMap(map, table.configWiring());
        addToObservationMap(map, table.readmeMentions());
        addToObservationMap(map, table.moduleExports());
        addToObservationMap(map, table.moduleUses());
        addToObservationMap(map, table.moduleProvides());
        return map;
    }

    private void addToObservationMap(Map<String, ObservationFact> map, List<ObservationFact> observations) {
        if (observations == null) return;
        for (ObservationFact o : observations) {
            if (o != null) map.put(observationKey(o), o);
        }
    }
}
