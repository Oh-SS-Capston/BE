package com.example.ossdoc.domain.extraction.service.composer;

import com.example.ossdoc.domain.extraction.dto.model.BuildMeta;
import com.example.ossdoc.domain.extraction.dto.model.EvidenceFact;
import com.example.ossdoc.domain.extraction.dto.model.JobMeta;
import com.example.ossdoc.domain.extraction.dto.model.ObservationFact;
import com.example.ossdoc.domain.extraction.dto.model.ObservationTable;
import com.example.ossdoc.domain.extraction.dto.model.RelationFact;
import com.example.ossdoc.domain.extraction.dto.model.RelationTable;
import com.example.ossdoc.domain.extraction.dto.model.SymbolFact;
import com.example.ossdoc.domain.extraction.dto.model.SymbolTable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * facts.json 각 section을 비어 있지 않은 표준 형태로 조립한다.
 */
final class FactsSectionFactory {

    private static final Comparator<SymbolFact> SYMBOL_ORDER = Comparator
            .comparing((SymbolFact it) -> safe(it.symbol()))
            .thenComparing(it -> safe(it.qualifiedName()))
            .thenComparing(it -> safe(it.name()));

    private static final Comparator<RelationFact> RELATION_ORDER = Comparator
            .comparing((RelationFact it) -> safe(it.srcSymbol()))
            .thenComparing(it -> safe(it.dstSymbol()))
            .thenComparing(it -> safe(it.dstRawRef()))
            .thenComparing(it -> safe(it.kind() == null ? null : it.kind().code()));

    private static final Comparator<ObservationFact> OBSERVATION_ORDER = Comparator
            .comparing((ObservationFact it) -> safe(it.siteSymbol()))
            .thenComparing(it -> safe(it.targetSymbol()))
            .thenComparing(it -> safe(it.serviceInterfaceSymbol()))
            .thenComparing(it -> safe(it.kind() == null ? null : it.kind().code()));

    JobMeta normalizeJob(JobMeta raw) {
        if (raw == null) {
            return JobMeta.builder().build();
        }
        return JobMeta.builder()
                .jobId(raw.jobId())
                .repoUrl(raw.repoUrl())
                .commitSha(raw.commitSha())
                .build();
    }

    BuildMeta normalizeBuild(BuildMeta raw) {
        if (raw == null) {
            return emptyBuild();
        }
        return BuildMeta.builder()
                .status(raw.status())
                .tool(raw.tool())
                .javaVersionUsed(raw.javaVersionUsed())
                .classpathFingerprint(raw.classpathFingerprint())
                .modules(normalizeStringList(raw.modules()))
                .bytecodeRoots(normalizeStringList(raw.bytecodeRoots()))
                .classpathEntries(normalizeStringList(raw.classpathEntries()))
                .build();
    }

    Map<String, EvidenceFact> composeEvidence(Map<String, EvidenceFact> rawEvidence) {
        LinkedHashMap<String, EvidenceFact> merged = new LinkedHashMap<>();
        if (rawEvidence != null) {
            for (Map.Entry<String, EvidenceFact> entry : rawEvidence.entrySet()) {
                if (entry.getValue() == null) {
                    continue;
                }
                String key = entry.getKey();
                if (key == null || key.isBlank()) {
                    key = FactsDedupSupport.evidenceKey(entry.getValue());
                }
                merged.merge(key, entry.getValue(), FactsDedupSupport::mergeEvidence);
            }
        }

        Map<String, EvidenceFact> sorted = new TreeMap<>();
        for (Map.Entry<String, EvidenceFact> entry : merged.entrySet()) {
            EvidenceFact normalized = FactsDedupSupport.mergeEvidence(
                    EvidenceFact.builder().id(entry.getKey()).build(),
                    entry.getValue()
            );
            sorted.put(entry.getKey(), normalized);
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    SymbolTable composeSymbols(SymbolTable raw) {
        LinkedHashMap<String, SymbolFact> merged = new LinkedHashMap<>();
        for (SymbolFact symbol : flattenSymbols(raw)) {
            merged.merge(FactsDedupSupport.symbolKey(symbol), symbol, FactsDedupSupport::mergeSymbol);
        }

        List<SymbolFact> modules = new ArrayList<>();
        List<SymbolFact> packages = new ArrayList<>();
        List<SymbolFact> types = new ArrayList<>();
        List<SymbolFact> constructors = new ArrayList<>();
        List<SymbolFact> methods = new ArrayList<>();
        List<SymbolFact> fields = new ArrayList<>();

        for (SymbolFact symbol : merged.values()) {
            if (symbol == null || symbol.kind() == null) {
                continue;
            }
            switch (symbol.kind()) {
                case MODULE -> modules.add(symbol);
                case PACKAGE -> packages.add(symbol);
                case TYPE -> types.add(symbol);
                case CONSTRUCTOR -> constructors.add(symbol);
                case METHOD -> methods.add(symbol);
                case FIELD -> fields.add(symbol);
            }
        }

        modules.sort(SYMBOL_ORDER);
        packages.sort(SYMBOL_ORDER);
        types.sort(SYMBOL_ORDER);
        constructors.sort(SYMBOL_ORDER);
        methods.sort(SYMBOL_ORDER);
        fields.sort(SYMBOL_ORDER);

        return SymbolTable.builder()
                .modules(List.copyOf(modules))
                .packages(List.copyOf(packages))
                .types(List.copyOf(types))
                .constructors(List.copyOf(constructors))
                .methods(List.copyOf(methods))
                .fields(List.copyOf(fields))
                .build();
    }

    RelationTable composeRelations(RelationTable raw) {
        LinkedHashMap<String, RelationFact> merged = new LinkedHashMap<>();
        for (RelationFact relation : flattenRelations(raw)) {
            merged.merge(FactsDedupSupport.relationKey(relation), relation, FactsDedupSupport::mergeRelation);
        }

        List<RelationFact> contains = new ArrayList<>();
        List<RelationFact> extendsRelations = new ArrayList<>();
        List<RelationFact> implementsRelations = new ArrayList<>();
        List<RelationFact> overrides = new ArrayList<>();
        List<RelationFact> calls = new ArrayList<>();
        List<RelationFact> accessesField = new ArrayList<>();
        List<RelationFact> fieldType = new ArrayList<>();
        List<RelationFact> paramType = new ArrayList<>();
        List<RelationFact> returnType = new ArrayList<>();
        List<RelationFact> throwsType = new ArrayList<>();
        List<RelationFact> annotatedBy = new ArrayList<>();

        for (RelationFact relation : merged.values()) {
            if (relation == null || relation.kind() == null) {
                continue;
            }
            switch (relation.kind()) {
                case CONTAINS -> contains.add(relation);
                case EXTENDS -> extendsRelations.add(relation);
                case IMPLEMENTS -> implementsRelations.add(relation);
                case OVERRIDES -> overrides.add(relation);
                case CALLS -> calls.add(relation);
                case ACCESSES_FIELD -> accessesField.add(relation);
                case FIELD_TYPE -> fieldType.add(relation);
                case PARAM_TYPE -> paramType.add(relation);
                case RETURN_TYPE -> returnType.add(relation);
                case THROWS_TYPE -> throwsType.add(relation);
                case ANNOTATED_BY -> annotatedBy.add(relation);
            }
        }

        contains.sort(RELATION_ORDER);
        extendsRelations.sort(RELATION_ORDER);
        implementsRelations.sort(RELATION_ORDER);
        overrides.sort(RELATION_ORDER);
        calls.sort(RELATION_ORDER);
        accessesField.sort(RELATION_ORDER);
        fieldType.sort(RELATION_ORDER);
        paramType.sort(RELATION_ORDER);
        returnType.sort(RELATION_ORDER);
        throwsType.sort(RELATION_ORDER);
        annotatedBy.sort(RELATION_ORDER);

        return RelationTable.builder()
                .contains(List.copyOf(contains))
                .extendsRelations(List.copyOf(extendsRelations))
                .implementsRelations(List.copyOf(implementsRelations))
                .overrides(List.copyOf(overrides))
                .calls(List.copyOf(calls))
                .accessesField(List.copyOf(accessesField))
                .fieldType(List.copyOf(fieldType))
                .paramType(List.copyOf(paramType))
                .returnType(List.copyOf(returnType))
                .throwsType(List.copyOf(throwsType))
                .annotatedBy(List.copyOf(annotatedBy))
                .build();
    }

    ObservationTable composeObservations(ObservationTable raw) {
        LinkedHashMap<String, ObservationFact> merged = new LinkedHashMap<>();
        for (ObservationFact observation : flattenObservations(raw)) {
            merged.merge(FactsDedupSupport.observationKey(observation), observation, FactsDedupSupport::mergeObservation);
        }

        List<ObservationFact> diInjectionSites = new ArrayList<>();
        List<ObservationFact> diProviders = new ArrayList<>();
        List<ObservationFact> spiProviders = new ArrayList<>();
        List<ObservationFact> eventPublish = new ArrayList<>();
        List<ObservationFact> eventSubscribe = new ArrayList<>();
        List<ObservationFact> reflectionUses = new ArrayList<>();
        List<ObservationFact> configWiring = new ArrayList<>();

        for (ObservationFact observation : merged.values()) {
            if (observation == null || observation.kind() == null) {
                continue;
            }
            switch (observation.kind()) {
                case DI_INJECTION_SITE -> diInjectionSites.add(observation);
                case DI_PROVIDER -> diProviders.add(observation);
                case SPI_PROVIDER -> spiProviders.add(observation);
                case EVENT_PUBLISH -> eventPublish.add(observation);
                case EVENT_SUBSCRIBE -> eventSubscribe.add(observation);
                case REFLECTION_USE -> reflectionUses.add(observation);
                case CONFIG_WIRING -> configWiring.add(observation);
            }
        }

        diInjectionSites.sort(OBSERVATION_ORDER);
        diProviders.sort(OBSERVATION_ORDER);
        spiProviders.sort(OBSERVATION_ORDER);
        eventPublish.sort(OBSERVATION_ORDER);
        eventSubscribe.sort(OBSERVATION_ORDER);
        reflectionUses.sort(OBSERVATION_ORDER);
        configWiring.sort(OBSERVATION_ORDER);

        return ObservationTable.builder()
                .diInjectionSites(List.copyOf(diInjectionSites))
                .diProviders(List.copyOf(diProviders))
                .spiProviders(List.copyOf(spiProviders))
                .eventPublish(List.copyOf(eventPublish))
                .eventSubscribe(List.copyOf(eventSubscribe))
                .reflectionUses(List.copyOf(reflectionUses))
                .configWiring(List.copyOf(configWiring))
                .build();
    }

    ObservationTable emptyObservationTable() {
        return ObservationTable.builder()
                .diInjectionSites(List.of())
                .diProviders(List.of())
                .spiProviders(List.of())
                .eventPublish(List.of())
                .eventSubscribe(List.of())
                .reflectionUses(List.of())
                .configWiring(List.of())
                .build();
    }

    private BuildMeta emptyBuild() {
        return BuildMeta.builder()
                .modules(List.of())
                .bytecodeRoots(List.of())
                .classpathEntries(List.of())
                .build();
    }

    private List<SymbolFact> flattenSymbols(SymbolTable table) {
        if (table == null) {
            return List.of();
        }
        List<SymbolFact> all = new ArrayList<>();
        addAll(all, table.modules());
        addAll(all, table.packages());
        addAll(all, table.types());
        addAll(all, table.constructors());
        addAll(all, table.methods());
        addAll(all, table.fields());
        return all;
    }

    private List<RelationFact> flattenRelations(RelationTable table) {
        if (table == null) {
            return List.of();
        }
        List<RelationFact> all = new ArrayList<>();
        addAll(all, table.contains());
        addAll(all, table.extendsRelations());
        addAll(all, table.implementsRelations());
        addAll(all, table.overrides());
        addAll(all, table.calls());
        addAll(all, table.accessesField());
        addAll(all, table.fieldType());
        addAll(all, table.paramType());
        addAll(all, table.returnType());
        addAll(all, table.throwsType());
        addAll(all, table.annotatedBy());
        return all;
    }

    private List<ObservationFact> flattenObservations(ObservationTable table) {
        if (table == null) {
            return List.of();
        }
        List<ObservationFact> all = new ArrayList<>();
        addAll(all, table.diInjectionSites());
        addAll(all, table.diProviders());
        addAll(all, table.spiProviders());
        addAll(all, table.eventPublish());
        addAll(all, table.eventSubscribe());
        addAll(all, table.reflectionUses());
        addAll(all, table.configWiring());
        return all;
    }

    private List<String> normalizeStringList(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : raw) {
            if (value == null || value.isBlank()) {
                continue;
            }
            result.add(value);
        }
        return List.copyOf(result);
    }

    private static <T> void addAll(List<T> target, List<T> source) {
        if (source != null && !source.isEmpty()) {
            target.addAll(source);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
