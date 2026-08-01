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
import com.example.ossdoc.domain.extraction.dto.model.TypeRef;
import com.example.ossdoc.domain.extraction.enums.ChunkStatus;
import com.example.ossdoc.domain.extraction.enums.FactOriginKind;
import com.example.ossdoc.domain.extraction.enums.ObservationKind;
import com.example.ossdoc.domain.extraction.enums.RelationKind;
import com.example.ossdoc.domain.extraction.enums.SymbolKind;
import com.example.ossdoc.domain.extraction.service.support.merge.StatsAccumulator;
import com.example.ossdoc.domain.extraction.service.support.util.WarningCollector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    private final Map<String, RelationFact> creates = new LinkedHashMap<>();
    private final Map<String, RelationFact> overrides = new LinkedHashMap<>();
    private final Map<String, RelationFact> accessesField = new LinkedHashMap<>();
    private final Map<String, RelationFact> annotatedWith = new LinkedHashMap<>();
    private final Map<String, RelationFact> handlesEndpoint = new LinkedHashMap<>();
    private final Map<String, RelationFact> declaresBean = new LinkedHashMap<>();
    private final Map<String, RelationFact> configuresBean = new LinkedHashMap<>();
    private final Map<String, RelationFact> injects = new LinkedHashMap<>();
    private final Map<String, RelationFact> publishesEvent = new LinkedHashMap<>();
    private final Map<String, RelationFact> listensEvent = new LinkedHashMap<>();
    private final Map<String, RelationFact> providesSpi = new LinkedHashMap<>();
    private final Map<String, RelationFact> loadsService = new LinkedHashMap<>();
    private final Map<String, RelationFact> reflectsType = new LinkedHashMap<>();
    private final Map<String, RelationFact> reflectsMethod = new LinkedHashMap<>();
    private final Map<String, RelationFact> reflectsField = new LinkedHashMap<>();
    private final Map<String, RelationFact> reflectsConstructor = new LinkedHashMap<>();

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
        if (fact == null
                || fact.symbol() == null
                || fact.symbol().isBlank()
                || fact.kind() == null) {
            return;
        }

        Map<String, SymbolFact> bucket = symbolBucket(fact.kind());
        if (!bucket.containsKey(fact.symbol())) {
            bucket.put(fact.symbol(), fact);
            stats.recordSymbol(fact);
        }
    }

    public void addRelation(RelationFact fact) {
        if (fact == null
                || fact.kind() == null
                || fact.srcSymbol() == null
                || fact.srcSymbol().isBlank()) {
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
        boolean isNew = !bucket.containsKey(key);

        bucket.merge(
                key,
                fact,
                ExtractionSink::mergeObservation
        );

        if (isNew) {
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
                || !creates.isEmpty()
                || !overrides.isEmpty()
                || !accessesField.isEmpty()
                || !annotatedWith.isEmpty()
                || !handlesEndpoint.isEmpty()
                || !declaresBean.isEmpty()
                || !configuresBean.isEmpty()
                || !injects.isEmpty()
                || !publishesEvent.isEmpty()
                || !listensEvent.isEmpty()
                || !providesSpi.isEmpty()
                || !loadsService.isEmpty()
                || !reflectsType.isEmpty()
                || !reflectsMethod.isEmpty()
                || !reflectsField.isEmpty()
                || !reflectsConstructor.isEmpty()
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
                        .creates(List.copyOf(creates.values()))
                        .overrides(List.copyOf(overrides.values()))
                        .accessesField(List.copyOf(accessesField.values()))
                        .annotatedWith(List.copyOf(annotatedWith.values()))
                        .handlesEndpoint(List.copyOf(handlesEndpoint.values()))
                        .declaresBean(List.copyOf(declaresBean.values()))
                        .configuresBean(List.copyOf(configuresBean.values()))
                        .injects(List.copyOf(injects.values()))
                        .publishesEvent(List.copyOf(publishesEvent.values()))
                        .listensEvent(List.copyOf(listensEvent.values()))
                        .providesSpi(List.copyOf(providesSpi.values()))
                        .loadsService(List.copyOf(loadsService.values()))
                        .reflectsType(List.copyOf(reflectsType.values()))
                        .reflectsMethod(List.copyOf(reflectsMethod.values()))
                        .reflectsField(List.copyOf(reflectsField.values()))
                        .reflectsConstructor(List.copyOf(reflectsConstructor.values()))
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
        items.addAll(creates.values());
        items.addAll(overrides.values());
        items.addAll(accessesField.values());
        items.addAll(annotatedWith.values());
        items.addAll(handlesEndpoint.values());
        items.addAll(declaresBean.values());
        items.addAll(configuresBean.values());
        items.addAll(injects.values());
        items.addAll(publishesEvent.values());
        items.addAll(listensEvent.values());
        items.addAll(providesSpi.values());
        items.addAll(loadsService.values());
        items.addAll(reflectsType.values());
        items.addAll(reflectsMethod.values());
        items.addAll(reflectsField.values());
        items.addAll(reflectsConstructor.values());
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
            case CREATES -> creates;
            case OVERRIDES -> overrides;
            case ACCESSES_FIELD -> accessesField;
            case ANNOTATED_WITH -> annotatedWith;
            case HANDLES_ENDPOINT -> handlesEndpoint;
            case DECLARES_BEAN -> declaresBean;
            case CONFIGURES_BEAN -> configuresBean;
            case INJECTS -> injects;
            case PUBLISHES_EVENT -> publishesEvent;
            case LISTENS_EVENT -> listensEvent;
            case PROVIDES_SPI -> providesSpi;
            case LOADS_SERVICE -> loadsService;
            case REFLECTS_TYPE -> reflectsType;
            case REFLECTS_METHOD -> reflectsMethod;
            case REFLECTS_FIELD -> reflectsField;
            case REFLECTS_CONSTRUCTOR -> reflectsConstructor;
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
                semanticTypeRefKey(fact.targetTypeRef()),
                observationDiscriminator(fact)
        );
    }

    private String observationDiscriminator(ObservationFact fact) {
        if (fact == null
                || fact.kind() != ObservationKind.REFLECTION_SITE
                || fact.attrs() == null) {
            return "";
        }

        return String.join("~",
                attrValue(fact.attrs(), "reflection_kind"),
                attrValue(fact.attrs(), "api_method", "method"),
                attrValue(fact.attrs(), "target_type"),
                attrValue(fact.attrs(), "member_name"),
                attrValue(fact.attrs(), "scope"),
                attrValue(fact.attrs(), "descriptor")
        );
    }

    private String attrValue(Map<String, Object> attrs, String... keys) {
        if (attrs == null || keys == null) {
            return "";
        }
        for (String key : keys) {
            Object value = attrs.get(key);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return "";
    }

    /**
     * observation의 동일성에는 타입의 의미 정보만 사용한다.
     * AST의 sourceText와 ASM의 descriptor 차이 때문에 같은 타입이 분리되는 것을 막는다.
     */
    private String semanticTypeRefKey(TypeRef typeRef) {
        if (typeRef == null) {
            return "";
        }

        List<String> argumentKeys = new ArrayList<>();
        if (typeRef.args() != null) {
            for (TypeRef argument : typeRef.args()) {
                argumentKeys.add(semanticTypeRefKey(argument));
            }
        }

        return String.join("~",
                nullSafe(typeRef.raw()),
                String.join(",", argumentKeys),
                typeRef.arrayDim() == null
                        ? ""
                        : String.valueOf(typeRef.arrayDim()),
                typeRef.wildcard() == null
                        ? ""
                        : typeRef.wildcard().code()
        );
    }

    private static ObservationFact mergeObservation(
            ObservationFact existing,
            ObservationFact incoming
    ) {
        return ObservationFact.builder()
                .kind(existing.kind() != null
                        ? existing.kind()
                        : incoming.kind())
                .siteSymbol(firstNonBlank(
                        existing.siteSymbol(),
                        incoming.siteSymbol()
                ))
                .targetSymbol(firstNonBlank(
                        existing.targetSymbol(),
                        incoming.targetSymbol()
                ))
                .targetTypeRef(mergeTypeRef(
                        existing.targetTypeRef(),
                        incoming.targetTypeRef()
                ))
                .note(preferLonger(
                        existing.note(),
                        incoming.note()
                ))
                .evidenceIds(mergeEvidenceIds(
                        existing.evidenceIds(),
                        incoming.evidenceIds()
                ))
                .origin(mergeOrigin(
                        existing.origin(),
                        incoming.origin()
                ))
                .confidenceHint(max(
                        existing.confidenceHint(),
                        incoming.confidenceHint()
                ))
                .attrs(mergeMaps(
                        existing.attrs(),
                        incoming.attrs()
                ))
                .build();
    }

    private static TypeRef mergeTypeRef(
            TypeRef existing,
            TypeRef incoming
    ) {
        if (existing == null) {
            return incoming;
        }
        if (incoming == null) {
            return existing;
        }

        return TypeRef.builder()
                .raw(firstNonBlank(existing.raw(), incoming.raw()))
                .args(firstNonEmptyList(existing.args(), incoming.args()))
                .arrayDim(existing.arrayDim() != null
                        ? existing.arrayDim()
                        : incoming.arrayDim())
                .primitive(existing.primitive() != null
                        ? existing.primitive()
                        : incoming.primitive())
                .unresolved(mergeUnresolved(
                        existing.unresolved(),
                        incoming.unresolved()
                ))
                .sourceText(firstNonBlank(
                        existing.sourceText(),
                        incoming.sourceText()
                ))
                .wildcard(existing.wildcard() != null
                        ? existing.wildcard()
                        : incoming.wildcard())
                .build();
    }

    private static Boolean mergeUnresolved(
            Boolean existing,
            Boolean incoming
    ) {
        if (Boolean.FALSE.equals(existing)
                || Boolean.FALSE.equals(incoming)) {
            return Boolean.FALSE;
        }

        return existing != null ? existing : incoming;
    }

    private static <T> List<T> firstNonEmptyList(
            List<T> existing,
            List<T> incoming
    ) {
        if (existing != null && !existing.isEmpty()) {
            return existing;
        }
        if (incoming != null && !incoming.isEmpty()) {
            return incoming;
        }
        return List.of();
    }

    private static List<String> mergeEvidenceIds(
            List<String> existing,
            List<String> incoming
    ) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (existing != null) {
            merged.addAll(existing);
        }
        if (incoming != null) {
            merged.addAll(incoming);
        }
        return List.copyOf(merged);
    }

    private static Map<String, Object> mergeMaps(
            Map<String, Object> existing,
            Map<String, Object> incoming
    ) {
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>();
        if (existing != null) {
            merged.putAll(existing);
        }
        if (incoming != null) {
            merged.putAll(incoming);
        }
        return Map.copyOf(merged);
    }

    private static FactOriginKind mergeOrigin(
            FactOriginKind existing,
            FactOriginKind incoming
    ) {
        if (existing == null) {
            return incoming;
        }
        if (incoming == null || existing == incoming) {
            return existing;
        }

        if ((existing == FactOriginKind.AST
                && incoming == FactOriginKind.BYTECODE)
                || (existing == FactOriginKind.BYTECODE
                && incoming == FactOriginKind.AST)
                || (existing == FactOriginKind.AST_AND_BYTECODE
                && (incoming == FactOriginKind.AST
                || incoming == FactOriginKind.BYTECODE))
                || (incoming == FactOriginKind.AST_AND_BYTECODE
                && (existing == FactOriginKind.AST
                || existing == FactOriginKind.BYTECODE))) {
            return FactOriginKind.AST_AND_BYTECODE;
        }

        return existing;
    }

    private static Double max(Double existing, Double incoming) {
        if (existing == null) {
            return incoming;
        }
        if (incoming == null) {
            return existing;
        }
        return Math.max(existing, incoming);
    }

    private static String preferLonger(
            String existing,
            String incoming
    ) {
        if (existing == null || existing.isBlank()) {
            return incoming;
        }
        if (incoming == null || incoming.isBlank()) {
            return existing;
        }
        return incoming.length() > existing.length()
                ? incoming
                : existing;
    }

    private static String firstNonBlank(
            String existing,
            String incoming
    ) {
        if (existing != null && !existing.isBlank()) {
            return existing;
        }
        return incoming != null && !incoming.isBlank()
                ? incoming
                : null;
    }

    private String nullSafe(String value) {
        return Objects.toString(value, "");
    }
}
