package com.example.ossdoc.domain.extraction.dto.context;

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
import com.example.ossdoc.domain.extraction.enums.ChunkStatus;
import com.example.ossdoc.domain.extraction.enums.ObservationKind;
import com.example.ossdoc.domain.extraction.enums.RelationKind;
import com.example.ossdoc.domain.extraction.enums.SymbolKind;
import com.example.ossdoc.domain.extraction.service.support.merge.StatsAccumulator;
import com.example.ossdoc.domain.extraction.service.support.util.WarningCollector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * extractor 내부의 mutable collector.
 * 최종 반환 직전에 ChunkResult immutable 객체로 변환한다.
 */
public class ExtractionSink {

    private final Map<String, EvidenceFact> evidence = new LinkedHashMap<>();
    private final Map<String, SymbolFact> types = new LinkedHashMap<>();
    private final Map<String, SymbolFact> constructors = new LinkedHashMap<>();
    private final Map<String, SymbolFact> methods = new LinkedHashMap<>();
    private final Map<String, SymbolFact> fields = new LinkedHashMap<>();

    private final Map<String, RelationFact> calls = new LinkedHashMap<>();
    private final Map<String, RelationFact> overrides = new LinkedHashMap<>();
    private final Map<String, RelationFact> accessesField = new LinkedHashMap<>();

    private final Map<String, ObservationFact> diInjectionSites = new LinkedHashMap<>();
    private final Map<String, ObservationFact> diProviders = new LinkedHashMap<>();
    private final Map<String, ObservationFact> spiProviders = new LinkedHashMap<>();
    private final Map<String, ObservationFact> eventPublications = new LinkedHashMap<>();
    private final Map<String, ObservationFact> eventSubscriptions = new LinkedHashMap<>();
    private final Map<String, ObservationFact> reflectionSites = new LinkedHashMap<>();
    private final Map<String, ObservationFact> httpEndpoints = new LinkedHashMap<>();
    private final Map<String, ObservationFact> scheduling = new LinkedHashMap<>();
    private final Map<String, ObservationFact> asyncMethods = new LinkedHashMap<>();
    private final Map<String, ObservationFact> configWiring = new LinkedHashMap<>();
    private final Map<String, ObservationFact> readmeMentions = new LinkedHashMap<>();
    private final Map<String, ObservationFact> moduleExports = new LinkedHashMap<>();
    private final Map<String, ObservationFact> moduleUses = new LinkedHashMap<>();
    private final Map<String, ObservationFact> moduleProvides = new LinkedHashMap<>();

    private final StatsAccumulator stats = new StatsAccumulator();
    private final WarningCollector warnings = new WarningCollector();
    private final WarningCollector errors = new WarningCollector();

    public ExtractionSink() {
    }

    public void addWarning(String warning) {
        warnings.add(warning);
    }

    public void addWarnings(List<String> items) {
        warnings.addAll(items);
    }

    public void addError(String error) {
        errors.add(error);
        stats.recordError();
    }

    public void recordFileScanned() {
        stats.recordFileScanned();
    }

    public void recordFileParsed() {
        stats.recordFileParsed();
    }

    public void recordFileSkipped() {
        stats.recordFileSkipped();
    }

    public void recordError() {
        stats.recordError();
    }

    public void recordUnresolvedTypeRef() {
        stats.recordUnresolvedTypeRef();
    }

    public void recordTotalTypeRef() {
        stats.recordTotalTypeRef();
    }

    public void addEvidence(EvidenceFact fact) {
        if (fact == null || fact.id() == null || fact.id().isBlank()) {
            return;
        }
        if (!evidence.containsKey(fact.id())) {
            evidence.put(fact.id(), fact);
            stats.recordEvidence();
        }
    }

    public void addSymbol(SymbolFact fact) {
        if (fact == null || fact.symbol() == null || fact.symbol().isBlank() || fact.kind() == null) {
            return;
        }

        Map<String, SymbolFact> bucket = symbolBucket(fact.kind());
        if (!bucket.containsKey(fact.symbol())) {
            bucket.put(fact.symbol(), fact);
            stats.recordSymbol(fact);
        }
    }

    public void addRelation(RelationFact fact) {
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

    public void addObservation(ObservationFact fact) {
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

    public ChunkResult toChunkResult(ChunkDescriptor descriptor) {
        StatsMeta snapshot = stats.snapshot();
        List<String> errorList = errors.snapshot();

        boolean hasProducedFacts = !evidence.isEmpty()
                || !types.isEmpty()
                || !constructors.isEmpty()
                || !methods.isEmpty()
                || !fields.isEmpty()
                || !calls.isEmpty()
                || !overrides.isEmpty()
                || !accessesField.isEmpty()
                || !diInjectionSites.isEmpty()
                || !diProviders.isEmpty()
                || !spiProviders.isEmpty()
                || !eventPublications.isEmpty()
                || !eventSubscriptions.isEmpty()
                || !reflectionSites.isEmpty()
                || !httpEndpoints.isEmpty()
                || !scheduling.isEmpty()
                || !asyncMethods.isEmpty()
                || !configWiring.isEmpty()
                || !readmeMentions.isEmpty()
                || !moduleExports.isEmpty()
                || !moduleUses.isEmpty()
                || !moduleProvides.isEmpty();

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

    public ExtractedFacts toExtractedFacts() {
        return new ExtractedFacts(
                Map.copyOf(evidence),
                SymbolTable.builder()
                        .types(List.copyOf(types.values()))
                        .constructors(List.copyOf(constructors.values()))
                        .methods(List.copyOf(methods.values()))
                        .fields(List.copyOf(fields.values()))
                        .build(),
                RelationTable.builder()
                        .calls(List.copyOf(calls.values()))
                        .overrides(List.copyOf(overrides.values()))
                        .accessesField(List.copyOf(accessesField.values()))
                        .build(),
                ObservationTable.builder()
                        .diInjectionSites(List.copyOf(diInjectionSites.values()))
                        .diProviders(List.copyOf(diProviders.values()))
                        .spiProviders(List.copyOf(spiProviders.values()))
                        .eventPublications(List.copyOf(eventPublications.values()))
                        .eventSubscriptions(List.copyOf(eventSubscriptions.values()))
                        .reflectionSites(List.copyOf(reflectionSites.values()))
                        .httpEndpoints(List.copyOf(httpEndpoints.values()))
                        .scheduling(List.copyOf(scheduling.values()))
                        .asyncMethods(List.copyOf(asyncMethods.values()))
                        .configWiring(List.copyOf(configWiring.values()))
                        .readmeMentions(List.copyOf(readmeMentions.values()))
                        .moduleExports(List.copyOf(moduleExports.values()))
                        .moduleUses(List.copyOf(moduleUses.values()))
                        .moduleProvides(List.copyOf(moduleProvides.values()))
                        .build(),
                stats.snapshot(),
                warnings.snapshot(),
                errors.snapshot()
        );
    }

    private List<SymbolFact> allSymbols() {
        List<SymbolFact> items = new ArrayList<>();
        items.addAll(types.values());
        items.addAll(constructors.values());
        items.addAll(methods.values());
        items.addAll(fields.values());
        return List.copyOf(items);
    }

    private List<RelationFact> allRelations() {
        List<RelationFact> items = new ArrayList<>();
        items.addAll(calls.values());
        items.addAll(overrides.values());
        items.addAll(accessesField.values());
        return List.copyOf(items);
    }

    private List<ObservationFact> allObservations() {
        List<ObservationFact> items = new ArrayList<>();
        items.addAll(diInjectionSites.values());
        items.addAll(diProviders.values());
        items.addAll(spiProviders.values());
        items.addAll(eventPublications.values());
        items.addAll(eventSubscriptions.values());
        items.addAll(reflectionSites.values());
        items.addAll(httpEndpoints.values());
        items.addAll(scheduling.values());
        items.addAll(asyncMethods.values());
        items.addAll(configWiring.values());
        items.addAll(readmeMentions.values());
        items.addAll(moduleExports.values());
        items.addAll(moduleUses.values());
        items.addAll(moduleProvides.values());
        return List.copyOf(items);
    }

    private Map<String, SymbolFact> symbolBucket(SymbolKind kind) {
        return switch (kind) {
            case TYPE -> types;
            case CONSTRUCTOR -> constructors;
            case METHOD -> methods;
            case FIELD -> fields;
        };
    }

    private Map<String, RelationFact> relationBucket(RelationKind kind) {
        return switch (kind) {
            case CALLS -> calls;
            case OVERRIDES -> overrides;
            case ACCESSES_FIELD -> accessesField;
        };
    }

    private Map<String, ObservationFact> observationBucket(ObservationKind kind) {
        return switch (kind) {
            case DI_INJECTION_SITE -> diInjectionSites;
            case DI_PROVIDER -> diProviders;
            case SPI_PROVIDER -> spiProviders;
            case EVENT_PUBLICATION -> eventPublications;
            case EVENT_SUBSCRIPTION -> eventSubscriptions;
            case REFLECTION_SITE -> reflectionSites;
            case HTTP_ENDPOINT -> httpEndpoints;
            case SCHEDULED_TASK -> scheduling;
            case ASYNC_METHOD -> asyncMethods;
            case CONFIG_WIRING -> configWiring;
            case README_MENTION -> readmeMentions;
            case MODULE_EXPORTS -> moduleExports;
            case MODULE_USES -> moduleUses;
            case MODULE_PROVIDES -> moduleProvides;
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
                nullSafe(fact.targetTypeRef() == null ? null : fact.targetTypeRef().raw()),
                nullSafe(fact.note())
        );
    }

    private String nullSafe(String value) {
        return Objects.toString(value, "");
    }
}
