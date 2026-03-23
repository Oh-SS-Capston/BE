package com.example.ossdoc.domain.extraction.service.extractor;

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
import com.example.ossdoc.domain.extraction.enums.ChunkKind;
import com.example.ossdoc.domain.extraction.enums.ChunkStatus;
import com.example.ossdoc.domain.extraction.enums.ObservationKind;
import com.example.ossdoc.domain.extraction.enums.RelationKind;
import com.example.ossdoc.domain.extraction.enums.SymbolFactKind;
import com.example.ossdoc.domain.extraction.service.support.StatsAccumulator;
import com.example.ossdoc.domain.extraction.service.support.WarningCollector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * extractor 내부의 mutable collector.
 * 최종 반환 직전에 ChunkResult immutable 객체로 변환한다.
 */
class ExtractionSink {

    private final ChunkKind chunkKind;

    private final Map<String, EvidenceFact> evidence = new LinkedHashMap<>();
    private final Map<String, SymbolFact> modules = new LinkedHashMap<>();
    private final Map<String, SymbolFact> packages = new LinkedHashMap<>();
    private final Map<String, SymbolFact> types = new LinkedHashMap<>();
    private final Map<String, SymbolFact> constructors = new LinkedHashMap<>();
    private final Map<String, SymbolFact> methods = new LinkedHashMap<>();
    private final Map<String, SymbolFact> fields = new LinkedHashMap<>();

    private final Map<String, RelationFact> contains = new LinkedHashMap<>();
    private final Map<String, RelationFact> extendsRelations = new LinkedHashMap<>();
    private final Map<String, RelationFact> implementsRelations = new LinkedHashMap<>();
    private final Map<String, RelationFact> overrides = new LinkedHashMap<>();
    private final Map<String, RelationFact> calls = new LinkedHashMap<>();
    private final Map<String, RelationFact> accessesField = new LinkedHashMap<>();
    private final Map<String, RelationFact> fieldType = new LinkedHashMap<>();
    private final Map<String, RelationFact> paramType = new LinkedHashMap<>();
    private final Map<String, RelationFact> returnType = new LinkedHashMap<>();
    private final Map<String, RelationFact> throwsType = new LinkedHashMap<>();
    private final Map<String, RelationFact> annotatedBy = new LinkedHashMap<>();

    private final Map<String, ObservationFact> diInjectionSites = new LinkedHashMap<>();
    private final Map<String, ObservationFact> diProviders = new LinkedHashMap<>();
    private final Map<String, ObservationFact> spiProviders = new LinkedHashMap<>();
    private final Map<String, ObservationFact> eventPublish = new LinkedHashMap<>();
    private final Map<String, ObservationFact> eventSubscribe = new LinkedHashMap<>();
    private final Map<String, ObservationFact> reflectionUses = new LinkedHashMap<>();
    private final Map<String, ObservationFact> configWiring = new LinkedHashMap<>();

    private final StatsAccumulator stats = new StatsAccumulator();
    private final WarningCollector warnings = new WarningCollector();
    private final WarningCollector errors = new WarningCollector();

    ExtractionSink(ChunkKind chunkKind) {
        this.chunkKind = chunkKind;
    }

    void addWarning(String warning) {
        warnings.add(warning);
    }

    void addWarnings(List<String> items) {
        warnings.addAll(items);
    }

    void addError(String error) {
        errors.add(error);
        stats.recordError();
    }

    void recordFileScanned() {
        stats.recordScannedFileKind(chunkKind);
    }

    void recordFileParsed() {
        stats.recordParsedFileKind(chunkKind);
    }

    void recordFileSkipped() {
        stats.recordFileSkipped();
    }

    void recordError() {
        stats.recordError();
    }

    void recordUnresolvedTypeRef() {
        stats.recordUnresolvedTypeRef();
    }

    void addEvidence(EvidenceFact fact) {
        if (fact == null || fact.id() == null || fact.id().isBlank()) {
            return;
        }
        if (!evidence.containsKey(fact.id())) {
            evidence.put(fact.id(), fact);
            stats.recordEvidence();
        }
    }

    void addSymbol(SymbolFact fact) {
        if (fact == null || fact.symbol() == null || fact.symbol().isBlank() || fact.kind() == null) {
            return;
        }

        Map<String, SymbolFact> bucket = symbolBucket(fact.kind());
        if (!bucket.containsKey(fact.symbol())) {
            bucket.put(fact.symbol(), fact);
            stats.recordSymbol(fact);
        }
    }

    void addRelation(RelationFact fact) {
        if (fact == null || fact.kind() == null || fact.srcSymbol() == null || fact.srcSymbol().isBlank()) {
            return;
        }

        Map<String, RelationFact> bucket = relationBucket(fact.kind());
        String key = relationKey(fact);
        if (!bucket.containsKey(key)) {
            bucket.put(key, fact);
            stats.recordRelation();
        }
    }

    void addObservation(ObservationFact fact) {
        if (fact == null || fact.kind() == null) {
            return;
        }

        Map<String, ObservationFact> bucket = observationBucket(fact.kind());
        String key = observationKey(fact);
        if (!bucket.containsKey(key)) {
            bucket.put(key, fact);
            stats.recordObservation();
        }
    }

    ChunkResult toChunkResult(ChunkDescriptor descriptor) {
        StatsMeta snapshot = stats.snapshot();
        List<String> errorList = errors.snapshot();

        boolean hasProducedFacts = !evidence.isEmpty()
                || !modules.isEmpty()
                || !packages.isEmpty()
                || !types.isEmpty()
                || !constructors.isEmpty()
                || !methods.isEmpty()
                || !fields.isEmpty()
                || !contains.isEmpty()
                || !extendsRelations.isEmpty()
                || !implementsRelations.isEmpty()
                || !overrides.isEmpty()
                || !calls.isEmpty()
                || !accessesField.isEmpty()
                || !fieldType.isEmpty()
                || !paramType.isEmpty()
                || !returnType.isEmpty()
                || !throwsType.isEmpty()
                || !annotatedBy.isEmpty()
                || !diInjectionSites.isEmpty()
                || !diProviders.isEmpty()
                || !spiProviders.isEmpty()
                || !eventPublish.isEmpty()
                || !eventSubscribe.isEmpty()
                || !reflectionUses.isEmpty()
                || !configWiring.isEmpty();

        ChunkStatus status;
        if (errorList.isEmpty()) {
            status = ChunkStatus.SUCCEEDED;
        } else if (hasProducedFacts || snapshot.filesParsed() > 0) {
            status = ChunkStatus.PARTIAL;
        } else {
            status = ChunkStatus.FAILED;
        }

        return ChunkResult.builder()
                .descriptor(descriptor)
                .status(status)
                .evidence(Map.copyOf(evidence))
                .symbols(allSymbols())
                .relations(allRelations())
                .observations(allObservations())
                .stats(snapshot)
                .warnings(warnings.snapshot())
                .errors(errorList)
                .build();
    }

    ExtractedFacts toExtractedFacts() {
        return new ExtractedFacts(
                Map.copyOf(evidence),
                SymbolTable.builder()
                        .modules(List.copyOf(modules.values()))
                        .packages(List.copyOf(packages.values()))
                        .types(List.copyOf(types.values()))
                        .constructors(List.copyOf(constructors.values()))
                        .methods(List.copyOf(methods.values()))
                        .fields(List.copyOf(fields.values()))
                        .build(),
                RelationTable.builder()
                        .contains(List.copyOf(contains.values()))
                        .extendsRelations(List.copyOf(extendsRelations.values()))
                        .implementsRelations(List.copyOf(implementsRelations.values()))
                        .overrides(List.copyOf(overrides.values()))
                        .calls(List.copyOf(calls.values()))
                        .accessesField(List.copyOf(accessesField.values()))
                        .fieldType(List.copyOf(fieldType.values()))
                        .paramType(List.copyOf(paramType.values()))
                        .returnType(List.copyOf(returnType.values()))
                        .throwsType(List.copyOf(throwsType.values()))
                        .annotatedBy(List.copyOf(annotatedBy.values()))
                        .build(),
                ObservationTable.builder()
                        .diInjectionSites(List.copyOf(diInjectionSites.values()))
                        .diProviders(List.copyOf(diProviders.values()))
                        .spiProviders(List.copyOf(spiProviders.values()))
                        .eventPublish(List.copyOf(eventPublish.values()))
                        .eventSubscribe(List.copyOf(eventSubscribe.values()))
                        .reflectionUses(List.copyOf(reflectionUses.values()))
                        .configWiring(List.copyOf(configWiring.values()))
                        .build(),
                stats.snapshot(),
                warnings.snapshot(),
                errors.snapshot()
        );
    }

    private List<SymbolFact> allSymbols() {
        List<SymbolFact> items = new ArrayList<>();
        items.addAll(modules.values());
        items.addAll(packages.values());
        items.addAll(types.values());
        items.addAll(constructors.values());
        items.addAll(methods.values());
        items.addAll(fields.values());
        return List.copyOf(items);
    }

    private List<RelationFact> allRelations() {
        List<RelationFact> items = new ArrayList<>();
        items.addAll(contains.values());
        items.addAll(extendsRelations.values());
        items.addAll(implementsRelations.values());
        items.addAll(overrides.values());
        items.addAll(calls.values());
        items.addAll(accessesField.values());
        items.addAll(fieldType.values());
        items.addAll(paramType.values());
        items.addAll(returnType.values());
        items.addAll(throwsType.values());
        items.addAll(annotatedBy.values());
        return List.copyOf(items);
    }

    private List<ObservationFact> allObservations() {
        List<ObservationFact> items = new ArrayList<>();
        items.addAll(diInjectionSites.values());
        items.addAll(diProviders.values());
        items.addAll(spiProviders.values());
        items.addAll(eventPublish.values());
        items.addAll(eventSubscribe.values());
        items.addAll(reflectionUses.values());
        items.addAll(configWiring.values());
        return List.copyOf(items);
    }

    private Map<String, SymbolFact> symbolBucket(SymbolFactKind kind) {
        return switch (kind) {
            case MODULE -> modules;
            case PACKAGE -> packages;
            case TYPE -> types;
            case CONSTRUCTOR -> constructors;
            case METHOD -> methods;
            case FIELD -> fields;
        };
    }

    private Map<String, RelationFact> relationBucket(RelationKind kind) {
        return switch (kind) {
            case CONTAINS -> contains;
            case EXTENDS -> extendsRelations;
            case IMPLEMENTS -> implementsRelations;
            case OVERRIDES -> overrides;
            case CALLS -> calls;
            case ACCESSES_FIELD -> accessesField;
            case FIELD_TYPE -> fieldType;
            case PARAM_TYPE -> paramType;
            case RETURN_TYPE -> returnType;
            case THROWS_TYPE -> throwsType;
            case ANNOTATED_BY -> annotatedBy;
        };
    }

    private Map<String, ObservationFact> observationBucket(ObservationKind kind) {
        return switch (kind) {
            case DI_INJECTION_SITE -> diInjectionSites;
            case DI_PROVIDER -> diProviders;
            case SPI_PROVIDER -> spiProviders;
            case EVENT_PUBLISH -> eventPublish;
            case EVENT_SUBSCRIBE -> eventSubscribe;
            case REFLECTION_USE -> reflectionUses;
            case CONFIG_WIRING -> configWiring;
        };
    }

    private String relationKey(RelationFact fact) {
        return String.join("|",
                fact.kind().code(),
                nullSafe(fact.srcSymbol()),
                nullSafe(fact.dstSymbol()),
                nullSafe(fact.dstRawRef()),
                nullSafe(fact.origin() == null ? null : fact.origin().code())
        );
    }

    private String observationKey(ObservationFact fact) {
        return String.join("|",
                fact.kind().code(),
                nullSafe(fact.siteSymbol()),
                nullSafe(fact.targetSymbol()),
                nullSafe(fact.serviceInterfaceSymbol()),
                nullSafe(fact.targetTypeRef() == null ? null : fact.targetTypeRef().raw()),
                nullSafe(fact.note())
        );
    }

    private String nullSafe(String value) {
        return Objects.toString(value, "");
    }
}
