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
import com.example.ossdoc.domain.extraction.enums.EvidenceType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
                .modules(raw.modules() == null ? List.of() : List.copyOf(raw.modules()))
                .asmUnavailable(raw.asmUnavailable())
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

        return Collections.unmodifiableMap(new LinkedHashMap<>(new TreeMap<>(merged)));
    }

    SymbolTable composeSymbols(SymbolTable raw, Map<String, EvidenceFact> evidenceMap) {
        LinkedHashMap<String, SymbolFact> merged = new LinkedHashMap<>();
        for (SymbolFact symbol : flattenSymbols(raw)) {
            merged.merge(FactsDedupSupport.symbolKey(symbol), symbol, FactsDedupSupport::mergeSymbol);
        }
        Set<String> testEvidenceIds = testEvidenceIds(evidenceMap);

        List<SymbolFact> types = new ArrayList<>();
        List<SymbolFact> constructors = new ArrayList<>();
        List<SymbolFact> methods = new ArrayList<>();
        List<SymbolFact> fields = new ArrayList<>();

        for (SymbolFact symbol : merged.values()) {
            if (symbol == null || symbol.kind() == null) {
                continue;
            }
            SymbolFact enriched = applyTestCoverageHint(symbol, testEvidenceIds);
            switch (enriched.kind()) {
                case TYPE -> types.add(enriched);
                case CONSTRUCTOR -> constructors.add(enriched);
                case METHOD -> methods.add(enriched);
                case FIELD -> fields.add(enriched);
            }
        }

        types.sort(SYMBOL_ORDER);
        constructors.sort(SYMBOL_ORDER);
        methods.sort(SYMBOL_ORDER);
        fields.sort(SYMBOL_ORDER);

        return SymbolTable.builder()
                .types(List.copyOf(types))
                .constructors(List.copyOf(constructors))
                .methods(List.copyOf(methods))
                .fields(List.copyOf(fields))
                .build();
    }

    RelationTable composeRelations(RelationTable raw) {
        return composeRelations(raw, List.of());
    }

    RelationTable composeRelations(
            RelationTable raw,
            List<RelationFact> additionalRelations
    ) {
        LinkedHashMap<String, RelationFact> merged = new LinkedHashMap<>();

        for (RelationFact relation : flattenRelations(raw)) {
            merged.merge(
                    FactsDedupSupport.relationKey(relation),
                    relation,
                    FactsDedupSupport::mergeRelation
            );
        }

        if (additionalRelations != null) {
            for (RelationFact relation : additionalRelations) {
                if (relation == null) {
                    continue;
                }
                merged.merge(
                        FactsDedupSupport.relationKey(relation),
                        relation,
                        FactsDedupSupport::mergeRelation
                );
            }
        }

        List<RelationFact> calls = new ArrayList<>();
        List<RelationFact> creates = new ArrayList<>();
        List<RelationFact> overrides = new ArrayList<>();
        List<RelationFact> accessesField = new ArrayList<>();
        List<RelationFact> annotatedWith = new ArrayList<>();
        List<RelationFact> handlesEndpoint = new ArrayList<>();
        List<RelationFact> declaresBean = new ArrayList<>();
        List<RelationFact> configuresBean = new ArrayList<>();
        List<RelationFact> injects = new ArrayList<>();
        List<RelationFact> publishesEvent = new ArrayList<>();
        List<RelationFact> listensEvent = new ArrayList<>();
        List<RelationFact> providesSpi = new ArrayList<>();
        List<RelationFact> loadsService = new ArrayList<>();
        List<RelationFact> reflectsType = new ArrayList<>();
        List<RelationFact> reflectsMethod = new ArrayList<>();
        List<RelationFact> reflectsField = new ArrayList<>();
        List<RelationFact> reflectsConstructor = new ArrayList<>();

        for (RelationFact relation : merged.values()) {
            if (relation == null || relation.kind() == null) {
                continue;
            }

            switch (relation.kind()) {
                case CALLS -> calls.add(relation);
                case CREATES -> creates.add(relation);
                case OVERRIDES -> overrides.add(relation);
                case ACCESSES_FIELD -> accessesField.add(relation);
                case ANNOTATED_WITH -> annotatedWith.add(relation);
                case HANDLES_ENDPOINT -> handlesEndpoint.add(relation);
                case DECLARES_BEAN -> declaresBean.add(relation);
                case CONFIGURES_BEAN -> configuresBean.add(relation);
                case INJECTS -> injects.add(relation);
                case PUBLISHES_EVENT -> publishesEvent.add(relation);
                case LISTENS_EVENT -> listensEvent.add(relation);
                case PROVIDES_SPI -> providesSpi.add(relation);
                case LOADS_SERVICE -> loadsService.add(relation);
                case REFLECTS_TYPE -> reflectsType.add(relation);
                case REFLECTS_METHOD -> reflectsMethod.add(relation);
                case REFLECTS_FIELD -> reflectsField.add(relation);
                case REFLECTS_CONSTRUCTOR -> reflectsConstructor.add(relation);
            }
        }

        calls.sort(RELATION_ORDER);
        creates.sort(RELATION_ORDER);
        overrides.sort(RELATION_ORDER);
        accessesField.sort(RELATION_ORDER);
        annotatedWith.sort(RELATION_ORDER);
        handlesEndpoint.sort(RELATION_ORDER);
        declaresBean.sort(RELATION_ORDER);
        configuresBean.sort(RELATION_ORDER);
        injects.sort(RELATION_ORDER);
        publishesEvent.sort(RELATION_ORDER);
        listensEvent.sort(RELATION_ORDER);
        providesSpi.sort(RELATION_ORDER);
        loadsService.sort(RELATION_ORDER);
        reflectsType.sort(RELATION_ORDER);
        reflectsMethod.sort(RELATION_ORDER);
        reflectsField.sort(RELATION_ORDER);
        reflectsConstructor.sort(RELATION_ORDER);

        return RelationTable.builder()
                .calls(List.copyOf(calls))
                .creates(List.copyOf(creates))
                .overrides(List.copyOf(overrides))
                .accessesField(List.copyOf(accessesField))
                .annotatedWith(List.copyOf(annotatedWith))
                .handlesEndpoint(List.copyOf(handlesEndpoint))
                .declaresBean(List.copyOf(declaresBean))
                .configuresBean(List.copyOf(configuresBean))
                .injects(List.copyOf(injects))
                .publishesEvent(List.copyOf(publishesEvent))
                .listensEvent(List.copyOf(listensEvent))
                .providesSpi(List.copyOf(providesSpi))
                .loadsService(List.copyOf(loadsService))
                .reflectsType(List.copyOf(reflectsType))
                .reflectsMethod(List.copyOf(reflectsMethod))
                .reflectsField(List.copyOf(reflectsField))
                .reflectsConstructor(List.copyOf(reflectsConstructor))
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
        List<ObservationFact> eventPublications = new ArrayList<>();
        List<ObservationFact> eventSubscriptions = new ArrayList<>();
        List<ObservationFact> reflectionSites = new ArrayList<>();
        List<ObservationFact> httpEndpoints = new ArrayList<>();
        List<ObservationFact> scheduling = new ArrayList<>();
        List<ObservationFact> asyncMethods = new ArrayList<>();
        List<ObservationFact> configWiring = new ArrayList<>();
        List<ObservationFact> readmeMentions = new ArrayList<>();
        List<ObservationFact> moduleExports = new ArrayList<>();
        List<ObservationFact> moduleUses = new ArrayList<>();
        List<ObservationFact> moduleProvides = new ArrayList<>();

        for (ObservationFact observation : merged.values()) {
            if (observation == null || observation.kind() == null) {
                continue;
            }
            switch (observation.kind()) {
                case DI_INJECTION_SITE -> diInjectionSites.add(observation);
                case DI_PROVIDER -> diProviders.add(observation);
                case SPI_PROVIDER -> spiProviders.add(observation);
                case EVENT_PUBLICATION -> eventPublications.add(observation);
                case EVENT_SUBSCRIPTION -> eventSubscriptions.add(observation);
                case REFLECTION_SITE -> reflectionSites.add(observation);
                case HTTP_ENDPOINT -> httpEndpoints.add(observation);
                case SCHEDULED_TASK -> scheduling.add(observation);
                case ASYNC_METHOD -> asyncMethods.add(observation);
                case CONFIG_WIRING -> configWiring.add(observation);
                case README_MENTION -> readmeMentions.add(observation);
                case MODULE_EXPORTS -> moduleExports.add(observation);
                case MODULE_USES -> moduleUses.add(observation);
                case MODULE_PROVIDES -> moduleProvides.add(observation);
            }
        }

        diInjectionSites.sort(OBSERVATION_ORDER);
        diProviders.sort(OBSERVATION_ORDER);
        spiProviders.sort(OBSERVATION_ORDER);
        eventPublications.sort(OBSERVATION_ORDER);
        eventSubscriptions.sort(OBSERVATION_ORDER);
        reflectionSites.sort(OBSERVATION_ORDER);
        httpEndpoints.sort(OBSERVATION_ORDER);
        scheduling.sort(OBSERVATION_ORDER);
        asyncMethods.sort(OBSERVATION_ORDER);
        configWiring.sort(OBSERVATION_ORDER);
        readmeMentions.sort(OBSERVATION_ORDER);
        moduleExports.sort(OBSERVATION_ORDER);
        moduleUses.sort(OBSERVATION_ORDER);
        moduleProvides.sort(OBSERVATION_ORDER);

        return ObservationTable.builder()
                .diInjectionSites(List.copyOf(diInjectionSites))
                .diProviders(List.copyOf(diProviders))
                .spiProviders(List.copyOf(spiProviders))
                .eventPublications(List.copyOf(eventPublications))
                .eventSubscriptions(List.copyOf(eventSubscriptions))
                .reflectionSites(List.copyOf(reflectionSites))
                .httpEndpoints(List.copyOf(httpEndpoints))
                .scheduling(List.copyOf(scheduling))
                .asyncMethods(List.copyOf(asyncMethods))
                .configWiring(List.copyOf(configWiring))
                .readmeMentions(List.copyOf(readmeMentions))
                .moduleExports(List.copyOf(moduleExports))
                .moduleUses(List.copyOf(moduleUses))
                .moduleProvides(List.copyOf(moduleProvides))
                .build();
    }

    ObservationTable emptyObservationTable() {
        return ObservationTable.builder()
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
                .build();
    }

    private Set<String> testEvidenceIds(Map<String, EvidenceFact> evidenceMap) {
        if (evidenceMap == null || evidenceMap.isEmpty()) {
            return Set.of();
        }

        Set<String> result = new LinkedHashSet<>();
        for (Map.Entry<String, EvidenceFact> entry : evidenceMap.entrySet()) {
            EvidenceFact evidence = entry.getValue();
            if (entry.getKey() != null && evidence != null && evidence.type() == EvidenceType.TEST) {
                result.add(entry.getKey());
            }
        }
        return Set.copyOf(result);
    }

    private SymbolFact applyTestCoverageHint(SymbolFact s, Set<String> testEvidenceIds) {
        if (s.evidenceIds() == null || s.evidenceIds().isEmpty()) return s;
        if (testEvidenceIds == null || testEvidenceIds.isEmpty()) return s;

        // TEST evidence id 목록은 composeSymbols 진입 시 한 번만 계산해
        // symbol마다 evidenceMap stream 조회를 반복하지 않는다.
        boolean hasTest = false;
        for (String evidenceId : s.evidenceIds()) {
            if (testEvidenceIds.contains(evidenceId)) {
                hasTest = true;
                break;
            }
        }
        if (!hasTest) return s;

        return SymbolFact.builder()
                .symbol(s.symbol()).kind(s.kind()).typeKind(s.typeKind())
                .name(s.name()).qualifiedName(s.qualifiedName())
                .ownerSymbol(s.ownerSymbol()).packageSymbol(s.packageSymbol())
                .module(s.module()).sourceRoot(s.sourceRoot()).bytecodeRoot(s.bytecodeRoot())
                .nestedIn(s.nestedIn()).access(s.access()).modifiers(s.modifiers())
                .origin(s.origin()).annotations(s.annotations())
                .evidenceIds(s.evidenceIds()).attrs(s.attrs())
                .signature(s.signature()).superTypeRef(s.superTypeRef())
                .interfaceTypeRefs(s.interfaceTypeRefs()).sourceFile(s.sourceFile())
                .docComment(s.docComment()).typeParams(s.typeParams())
                .testCoverageHint(Boolean.TRUE)
                .throwsUnchecked(s.throwsUnchecked())
                .hasConditionalThrow(s.hasConditionalThrow())
                .stateMutations(s.stateMutations())
                .build();
    }

    private BuildMeta emptyBuild() {
        return BuildMeta.builder()
                .modules(List.of())
                .build();
    }

    private List<SymbolFact> flattenSymbols(SymbolTable table) {
        if (table == null) {
            return List.of();
        }
        List<SymbolFact> all = new ArrayList<>();
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
        addAll(all, table.calls());
        addAll(all, table.creates());
        addAll(all, table.overrides());
        addAll(all, table.accessesField());
        addAll(all, table.annotatedWith());
        addAll(all, table.handlesEndpoint());
        addAll(all, table.declaresBean());
        addAll(all, table.configuresBean());
        addAll(all, table.injects());
        addAll(all, table.publishesEvent());
        addAll(all, table.listensEvent());
        addAll(all, table.providesSpi());
        addAll(all, table.loadsService());
        addAll(all, table.reflectsType());
        addAll(all, table.reflectsMethod());
        addAll(all, table.reflectsField());
        addAll(all, table.reflectsConstructor());
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
        addAll(all, table.eventPublications());
        addAll(all, table.eventSubscriptions());
        addAll(all, table.reflectionSites());
        addAll(all, table.httpEndpoints());
        addAll(all, table.scheduling());
        addAll(all, table.asyncMethods());
        addAll(all, table.configWiring());
        addAll(all, table.readmeMentions());
        addAll(all, table.moduleExports());
        addAll(all, table.moduleUses());
        addAll(all, table.moduleProvides());
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
