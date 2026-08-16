package com.example.ossdoc.domain.extraction.service.support.resolve;

import com.example.ossdoc.domain.extraction.dto.model.ObservationFact;
import com.example.ossdoc.domain.extraction.dto.model.ObservationTable;
import com.example.ossdoc.domain.extraction.dto.model.RelationFact;
import com.example.ossdoc.domain.extraction.dto.model.SymbolFact;
import com.example.ossdoc.domain.extraction.dto.model.SymbolTable;
import com.example.ossdoc.domain.extraction.dto.model.TypeRef;
import com.example.ossdoc.domain.extraction.enums.DerivationKind;
import com.example.ossdoc.domain.extraction.enums.FactOriginKind;
import com.example.ossdoc.domain.extraction.enums.ObservationKind;
import com.example.ossdoc.domain.extraction.enums.RelationKind;
import com.example.ossdoc.domain.extraction.service.support.policy.ConfidenceAssessment;
import com.example.ossdoc.domain.extraction.service.support.policy.RelationConfidencePolicy;
import com.example.ossdoc.domain.extraction.service.support.policy.RelationPolicyInput;
import com.example.ossdoc.domain.extraction.service.support.policy.RelationResolutionPolicy;
import com.example.ossdoc.domain.extraction.service.support.policy.ResolutionAssessment;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DI_INJECTION_SITE observation과 DI_PROVIDER observation을 매칭해
 * 소유 타입에서 실제 주입 대상 Bean/타입으로 향하는 INJECTS 관계를 생성한다.
 *
 * <p>후보가 여러 개일 때는 qualifier, parameter name, primary 순으로 좁힌다.
 * 그래도 후보가 하나로 확정되지 않으면 임의의 provider를 선택하지 않고
 * 타입 raw reference를 대상으로 PARTIAL 관계를 남긴다.</p>
 */
@Component
public class DiObservationResolver
        implements ObservationRelationResolver {

    private RelationResolutionPolicy resolutionPolicy;
    private RelationConfidencePolicy confidencePolicy;

    public DiObservationResolver() {
        this.resolutionPolicy = new RelationResolutionPolicy();
        this.confidencePolicy = new RelationConfidencePolicy();
    }

    /** 일반 애플리케이션에서는 Spring 공통 정책 Bean을 사용하고, 단위 테스트는 기본 정책으로 동작한다. */
    @Autowired(required = false)
    void configurePolicies(
            RelationResolutionPolicy resolutionPolicy,
            RelationConfidencePolicy confidencePolicy
    ) {
        if (resolutionPolicy != null) {
            this.resolutionPolicy = resolutionPolicy;
        }
        if (confidencePolicy != null) {
            this.confidencePolicy = confidencePolicy;
        }
    }

    @Override
    public Set<ObservationKind> supportedKinds() {
        return Set.of(ObservationKind.DI_INJECTION_SITE);
    }

    @Override
    public int order() {
        return 400;
    }

    @Override
    public ObservationResolutionResult resolve(
            ObservationResolutionContext context
    ) {
        if (context == null) {
            return new ObservationResolutionResult(
                    List.of(),
                    List.of("DI resolver received a null context")
            );
        }

        ObservationTable observations = context.observations();
        List<ObservationFact> injectionSites = observations == null
                || observations.diInjectionSites() == null
                ? List.of()
                : observations.diInjectionSites();

        if (injectionSites.isEmpty()) {
            return ObservationResolutionResult.empty();
        }

        Map<String, SymbolFact> symbolIndex = symbolIndex(context.symbols());
        List<ProviderCandidate> providers = providerCandidates(
                observations == null ? null : observations.diProviders(),
                symbolIndex
        );

        List<RelationFact> relations = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (ObservationFact injectionSite : injectionSites) {
            resolveInjectionSite(
                    injectionSite,
                    providers,
                    symbolIndex,
                    relations,
                    warnings
            );
        }

        return new ObservationResolutionResult(relations, warnings);
    }

    private void resolveInjectionSite(
            ObservationFact injection,
            List<ProviderCandidate> providers,
            Map<String, SymbolFact> symbolIndex,
            List<RelationFact> relations,
            List<String> warnings
    ) {
        if (injection == null) {
            return;
        }

        String injectionSiteSymbol = trimToNull(injection.siteSymbol());
        if (injectionSiteSymbol == null) {
            warnings.add(
                    "DI_INJECTION_SITE observation has no siteSymbol and was skipped"
            );
            return;
        }

        String sourceTypeSymbol = resolveOwnerTypeSymbol(
                injectionSiteSymbol,
                injection.attrs(),
                symbolIndex
        );
        if (sourceTypeSymbol == null) {
            warnings.add(
                    "DI_INJECTION_SITE owner type could not be resolved: "
                            + injectionSiteSymbol
            );
            return;
        }

        String injectionType = resolveInjectionType(injection);
        if (injectionType == null) {
            warnings.add(
                    "DI_INJECTION_SITE target type could not be resolved: "
                            + injectionSiteSymbol
            );
            return;
        }

        boolean unresolvedType = injection.targetTypeRef() != null
                && Boolean.TRUE.equals(injection.targetTypeRef().unresolved());

        CandidateMatch candidateMatch = matchingCandidates(
                providers,
                injectionType,
                unresolvedType
        );

        List<String> explicitNames = explicitInjectionNames(injection.attrs());
        String parameterName = firstString(injection.attrs(), "parameter");

        Selection selection = selectCandidate(
                candidateMatch.candidates(),
                explicitNames,
                parameterName,
                candidateMatch.simpleNameMatch()
        );

        if (selection.candidate() != null) {
            relations.add(buildResolvedRelation(
                    injection,
                    sourceTypeSymbol,
                    injectionSiteSymbol,
                    injectionType,
                    selection
            ));
            return;
        }

        if (!candidateMatch.candidates().isEmpty()) {
            relations.add(buildAmbiguousRelation(
                    injection,
                    sourceTypeSymbol,
                    injectionSiteSymbol,
                    injectionType,
                    candidateMatch.candidates(),
                    explicitNames,
                    parameterName,
                    selection
            ));
            return;
        }

        String internalTypeSymbol = resolveInternalTypeSymbol(
                injectionType,
                unresolvedType,
                symbolIndex
        );

        relations.add(buildFallbackRelation(
                injection,
                sourceTypeSymbol,
                injectionSiteSymbol,
                injectionType,
                internalTypeSymbol
        ));
    }

    private RelationFact buildResolvedRelation(
            ObservationFact injection,
            String sourceTypeSymbol,
            String injectionSiteSymbol,
            String injectionType,
            Selection selection
    ) {
        ProviderCandidate provider = selection.candidate();
        Destination destination = destinationOf(provider);
        List<String> evidenceIds = mergeEvidenceIds(
                injection.evidenceIds(),
                provider.observation().evidenceIds()
        );
        FactOriginKind origin = mergeOrigin(
                injection.origin(),
                provider.observation().origin()
        );

        boolean qualifierMatched = "qualifier".equals(selection.strategy());
        boolean primaryMatched = "primary".equals(selection.strategy());

        RelationPolicyInput policyInput = RelationPolicyInput.builder()
                .origin(origin)
                .derivation(DerivationKind.DERIVED)
                .targetSymbolResolved(destination.symbol() != null)
                .targetReferenceKnown(
                        destination.symbol() != null
                                || destination.rawRef() != null
                )
                .targetReferenceAuthoritative(destination.rawRef() != null)
                .inferred(selection.partial())
                .candidateCount(1)
                .qualifierMatched(qualifierMatched)
                .primaryMatched(primaryMatched)
                .evidencePresent(!evidenceIds.isEmpty())
                .sourceConfidenceHint(maxConfidenceHint(
                        injection.confidenceHint(),
                        provider.observation().confidenceHint()
                ))
                .build();

        ResolutionAssessment resolution = resolutionPolicy.assess(policyInput);
        if (selection.reason() != null) {
            resolution = new ResolutionAssessment(
                    resolution.status(),
                    resolution.basis(),
                    selection.reason()
            );
        }
        ConfidenceAssessment confidence = confidencePolicy.assess(
                policyInput,
                resolution
        );

        Map<String, Object> attrs = baseAttrs(
                injection,
                injectionSiteSymbol,
                injectionType
        );
        attrs.put("match_strategy", selection.strategy());
        attrs.put("provider_symbol", provider.siteSymbol());
        attrs.put("provided_type", provider.providedType());
        attrs.put("provider_kind", provider.providerKind());
        attrs.put("bean_names", provider.beanNames());
        attrs.put("qualifiers", provider.qualifiers());
        attrs.put("primary", provider.primary());
        attrs.put("candidate_count", selection.candidateCount());
        putPolicyAttrs(attrs, resolution, confidence);

        RelationFact.RelationFactBuilder builder = RelationFact.builder()
                .kind(RelationKind.INJECTS)
                .srcSymbol(sourceTypeSymbol)
                .evidenceIds(evidenceIds)
                .resolution(resolution.toRelationResolution())
                .origin(origin)
                .derivation(DerivationKind.DERIVED)
                .confidenceHint(confidence.value())
                .attrs(immutableAttrs(attrs));

        if (destination.symbol() != null) {
            builder.dstSymbol(destination.symbol());
        } else {
            builder.dstRawRef(destination.rawRef());
        }

        return builder.build();
    }

    private RelationFact buildAmbiguousRelation(
            ObservationFact injection,
            String sourceTypeSymbol,
            String injectionSiteSymbol,
            String injectionType,
            List<ProviderCandidate> candidates,
            List<String> explicitNames,
            String parameterName,
            Selection selection
    ) {
        String matchStrategy = selection.strategy() == null
                ? "ambiguous"
                : selection.strategy();
        String partialReason = selection.reason() == null
                ? "Multiple DI providers matched the injection type"
                : selection.reason();

        List<String> evidenceIds = mergeCandidateEvidence(
                injection.evidenceIds(),
                candidates
        );
        FactOriginKind origin = mergeCandidateOrigins(
                injection.origin(),
                candidates
        );

        RelationPolicyInput policyInput = RelationPolicyInput.builder()
                .origin(origin)
                .derivation(DerivationKind.DERIVED)
                .targetReferenceKnown(true)
                .targetReferenceAuthoritative(false)
                .inferred(false)
                .candidateCount(candidates.size())
                .evidencePresent(!evidenceIds.isEmpty())
                .sourceConfidenceHint(mergeCandidateConfidenceHint(
                        injection.confidenceHint(),
                        candidates
                ))
                .build();

        ResolutionAssessment resolution = resolutionPolicy.assess(policyInput);
        resolution = new ResolutionAssessment(
                resolution.status(),
                resolution.basis(),
                partialReason
        );
        ConfidenceAssessment confidence = confidencePolicy.assess(
                policyInput,
                resolution
        );

        Map<String, Object> attrs = baseAttrs(
                injection,
                injectionSiteSymbol,
                injectionType
        );
        attrs.put("match_strategy", matchStrategy);
        attrs.put("candidate_count", candidates.size());
        attrs.put(
                "candidate_provider_symbols",
                candidateProviderSymbols(candidates)
        );
        if (!explicitNames.isEmpty()) {
            attrs.put("injection_qualifiers", explicitNames);
        }
        if (parameterName != null) {
            attrs.put("parameter_name", parameterName);
        }
        putPolicyAttrs(attrs, resolution, confidence);

        return RelationFact.builder()
                .kind(RelationKind.INJECTS)
                .srcSymbol(sourceTypeSymbol)
                .dstRawRef("type:" + injectionType)
                .evidenceIds(evidenceIds)
                .resolution(resolution.toRelationResolution())
                .origin(origin)
                .derivation(DerivationKind.DERIVED)
                .confidenceHint(confidence.value())
                .attrs(immutableAttrs(attrs))
                .build();
    }

    private RelationFact buildFallbackRelation(
            ObservationFact injection,
            String sourceTypeSymbol,
            String injectionSiteSymbol,
            String injectionType,
            String internalTypeSymbol
    ) {
        List<String> evidenceIds = sanitizeEvidenceIds(injection.evidenceIds());
        FactOriginKind origin = injection.origin() == null
                ? FactOriginKind.OBSERVED
                : injection.origin();
        String reason = internalTypeSymbol == null
                ? "No DI provider observation matched the injection type"
                : "Internal type found but DI provider observation was absent";

        RelationPolicyInput policyInput = RelationPolicyInput.builder()
                .origin(origin)
                .derivation(DerivationKind.DERIVED)
                .targetSymbolResolved(internalTypeSymbol != null)
                .targetReferenceKnown(true)
                .targetReferenceAuthoritative(false)
                .inferred(true)
                .candidateCount(0)
                .evidencePresent(!evidenceIds.isEmpty())
                .sourceConfidenceHint(injection.confidenceHint())
                .build();

        ResolutionAssessment resolution = resolutionPolicy.assess(policyInput);
        resolution = new ResolutionAssessment(
                resolution.status(),
                resolution.basis(),
                reason
        );
        ConfidenceAssessment confidence = confidencePolicy.assess(
                policyInput,
                resolution
        );

        Map<String, Object> attrs = baseAttrs(
                injection,
                injectionSiteSymbol,
                injectionType
        );
        attrs.put(
                "match_strategy",
                internalTypeSymbol == null
                        ? "unresolved_provider"
                        : "internal_type_fallback"
        );
        attrs.put("candidate_count", 0);
        putPolicyAttrs(attrs, resolution, confidence);

        RelationFact.RelationFactBuilder builder = RelationFact.builder()
                .kind(RelationKind.INJECTS)
                .srcSymbol(sourceTypeSymbol)
                .evidenceIds(evidenceIds)
                .resolution(resolution.toRelationResolution())
                .origin(origin)
                .derivation(DerivationKind.DERIVED)
                .confidenceHint(confidence.value())
                .attrs(immutableAttrs(attrs));

        if (internalTypeSymbol != null) {
            builder.dstSymbol(internalTypeSymbol);
        } else {
            builder.dstRawRef("type:" + injectionType);
        }

        return builder.build();
    }

    private Selection selectCandidate(
            List<ProviderCandidate> candidates,
            List<String> explicitNames,
            String parameterName,
            boolean simpleNameMatch
    ) {
        if (candidates.isEmpty()) {
            return Selection.none();
        }

        if (!explicitNames.isEmpty()) {
            List<ProviderCandidate> qualified = candidatesByAnyName(
                    candidates,
                    explicitNames
            );
            if (qualified.size() == 1) {
                return Selection.resolved(
                        qualified.get(0),
                        "qualifier",
                        candidates.size()
                );
            }
            if (qualified.size() > 1) {
                candidates = qualified;
            } else if (qualified.isEmpty()) {
                return Selection.none(
                        "qualifier_mismatch",
                        "Injection qualifier did not match any DI provider candidate",
                        candidates.size()
                );
            }
        }

        if (parameterName != null && candidates.size() > 1) {
            List<ProviderCandidate> named = candidatesByName(
                    candidates,
                    parameterName
            );
            if (named.size() == 1) {
                return Selection.partial(
                        named.get(0),
                        "parameter_name",
                        "Provider selected by injection parameter name",
                        candidates.size()
                );
            }
            if (named.size() > 1) {
                candidates = named;
            }
        }

        if (candidates.size() == 1) {
            ProviderCandidate candidate = candidates.get(0);
            if (simpleNameMatch) {
                return Selection.partial(
                        candidate,
                        "simple_type_name",
                        "Provider matched by unresolved simple type name",
                        1
                );
            }
            return Selection.resolved(
                    candidate,
                    "exact_type",
                    1
            );
        }

        List<ProviderCandidate> primaryCandidates = primaryCandidates(
                candidates
        );
        if (primaryCandidates.size() == 1) {
            return Selection.resolved(
                    primaryCandidates.get(0),
                    "primary",
                    candidates.size()
            );
        }

        return Selection.none(
                "ambiguous",
                "Multiple DI providers matched the injection type",
                candidates.size()
        );
    }

    private CandidateMatch matchingCandidates(
            List<ProviderCandidate> providers,
            String injectionType,
            boolean unresolvedType
    ) {
        List<ProviderCandidate> exact = new ArrayList<>();
        for (ProviderCandidate candidate : providers) {
            if (candidate.exposesExact(injectionType)) {
                exact.add(candidate);
            }
        }
        if (!exact.isEmpty()) {
            return new CandidateMatch(List.copyOf(exact), false);
        }

        if (!unresolvedType) {
            return new CandidateMatch(List.of(), false);
        }

        List<ProviderCandidate> simple = new ArrayList<>();
        for (ProviderCandidate candidate : providers) {
            if (candidate.exposesSimple(injectionType)) {
                simple.add(candidate);
            }
        }
        List<ProviderCandidate> simpleMatches = List.copyOf(simple);
        return new CandidateMatch(simpleMatches, !simpleMatches.isEmpty());
    }

    private List<ProviderCandidate> candidatesByAnyName(
            List<ProviderCandidate> candidates,
            List<String> names
    ) {
        List<ProviderCandidate> matched = new ArrayList<>();
        for (ProviderCandidate candidate : candidates) {
            if (candidate.matchesAnyName(names)) {
                matched.add(candidate);
            }
        }
        return List.copyOf(matched);
    }

    private List<ProviderCandidate> candidatesByName(
            List<ProviderCandidate> candidates,
            String name
    ) {
        List<ProviderCandidate> matched = new ArrayList<>();
        for (ProviderCandidate candidate : candidates) {
            if (candidate.matchesName(name)) {
                matched.add(candidate);
            }
        }
        return List.copyOf(matched);
    }

    private List<ProviderCandidate> primaryCandidates(
            List<ProviderCandidate> candidates
    ) {
        List<ProviderCandidate> matched = new ArrayList<>();
        for (ProviderCandidate candidate : candidates) {
            if (candidate.primary()) {
                matched.add(candidate);
            }
        }
        return List.copyOf(matched);
    }

    private List<String> candidateProviderSymbols(
            List<ProviderCandidate> candidates
    ) {
        // 성능 최적화: 관계 attrs 생성도 injection site별 반복 경로라 stream 대신 단순 루프로 중간 객체 생성을 줄인다.
        List<String> symbols = new ArrayList<>(candidates.size());
        for (ProviderCandidate candidate : candidates) {
            symbols.add(candidate.siteSymbol());
        }
        return List.copyOf(symbols);
    }

    private List<ProviderCandidate> providerCandidates(
            List<ObservationFact> providerObservations,
            Map<String, SymbolFact> symbolIndex
    ) {
        if (providerObservations == null || providerObservations.isEmpty()) {
            return List.of();
        }

        List<ProviderCandidate> candidates = new ArrayList<>();
        for (ObservationFact provider : providerObservations) {
            ProviderCandidate candidate = toProviderCandidate(
                    provider,
                    symbolIndex
            );
            if (candidate != null) {
                candidates.add(candidate);
            }
        }
        return List.copyOf(candidates);
    }

    private ProviderCandidate toProviderCandidate(
            ObservationFact provider,
            Map<String, SymbolFact> symbolIndex
    ) {
        if (provider == null) {
            return null;
        }

        String siteSymbol = trimToNull(provider.siteSymbol());
        if (siteSymbol == null) {
            return null;
        }

        Map<String, Object> attrs = provider.attrs() == null
                ? Map.of()
                : provider.attrs();

        String providedType = firstNonBlank(
                stringValue(attrs.get("provided_type")),
                provider.targetTypeRef() == null
                        ? null
                        : provider.targetTypeRef().raw(),
                siteSymbol.startsWith("type:")
                        ? siteSymbol.substring("type:".length())
                        : null
        );
        providedType = normalizeType(providedType);

        LinkedHashSet<String> exposedTypes = new LinkedHashSet<>();
        addType(exposedTypes, providedType);

        SymbolFact providerSymbol = symbolIndex.get(siteSymbol);
        if (providerSymbol != null) {
            addType(exposedTypes, providerSymbol.qualifiedName());
            if (providerSymbol.superTypeRef() != null) {
                addType(exposedTypes, providerSymbol.superTypeRef().raw());
            }
            if (providerSymbol.interfaceTypeRefs() != null) {
                for (TypeRef interfaceType : providerSymbol.interfaceTypeRefs()) {
                    if (interfaceType != null) {
                        addType(exposedTypes, interfaceType.raw());
                    }
                }
            }
        }

        if (exposedTypes.isEmpty()) {
            return null;
        }

        List<String> beanNames = normalizeNames(
                stringList(attrs.get("bean_names"))
        );
        if (beanNames.isEmpty()) {
            String inferredName = inferBeanName(siteSymbol, providedType);
            if (inferredName != null) {
                beanNames = List.of(inferredName);
            }
        }

        List<String> qualifiers = normalizeNames(
                stringList(attrs.get("qualifiers"))
        );

        // 성능 최적화: unresolved type fallback은 provider 후보별 exposed type의 simple name을 반복 비교한다.
        // 후보 생성 시 한 번만 계산해두면 injection site마다 문자열 분해와 stream 순회를 다시 하지 않아도 된다.
        Set<String> exposedSimpleTypes = simpleTypeNames(exposedTypes);

        return new ProviderCandidate(
                provider,
                siteSymbol,
                providedType,
                Set.copyOf(exposedTypes),
                exposedSimpleTypes,
                beanNames,
                qualifiers,
                booleanValue(attrs.get("primary")),
                firstNonBlank(
                        stringValue(attrs.get("provider_kind")),
                        siteSymbol.startsWith("method:")
                                ? "provider_method"
                                : "component_type"
                )
        );
    }

    private Map<String, SymbolFact> symbolIndex(SymbolTable table) {
        Map<String, SymbolFact> index = new LinkedHashMap<>();
        if (table == null) {
            return index;
        }

        addSymbols(index, table.types());
        addSymbols(index, table.constructors());
        addSymbols(index, table.methods());
        addSymbols(index, table.fields());
        return index;
    }

    private void addSymbols(
            Map<String, SymbolFact> index,
            List<SymbolFact> symbols
    ) {
        if (symbols == null) {
            return;
        }
        for (SymbolFact symbol : symbols) {
            if (symbol == null) {
                continue;
            }
            String id = trimToNull(symbol.symbol());
            if (id != null) {
                index.put(id, symbol);
            }
        }
    }

    private String resolveOwnerTypeSymbol(
            String injectionSiteSymbol,
            Map<String, Object> attrs,
            Map<String, SymbolFact> symbolIndex
    ) {
        SymbolFact site = symbolIndex.get(injectionSiteSymbol);
        if (site != null) {
            String owner = trimToNull(site.ownerSymbol());
            if (owner != null) {
                return owner;
            }
            if (injectionSiteSymbol.startsWith("type:")) {
                return injectionSiteSymbol;
            }
        }

        String fromAttrs = firstNonBlank(
                firstString(attrs, "owner_type_symbol"),
                firstString(attrs, "owner_symbol")
        );
        if (fromAttrs != null) {
            return fromAttrs.startsWith("type:")
                    ? fromAttrs
                    : "type:" + normalizeType(fromAttrs);
        }

        if (injectionSiteSymbol.startsWith("type:")) {
            return injectionSiteSymbol;
        }

        int colonIndex = injectionSiteSymbol.indexOf(':');
        int hashIndex = injectionSiteSymbol.indexOf('#');
        if (colonIndex >= 0 && hashIndex > colonIndex + 1) {
            String ownerType = normalizeType(
                    injectionSiteSymbol.substring(colonIndex + 1, hashIndex)
            );
            return ownerType == null ? null : "type:" + ownerType;
        }

        return null;
    }

    private String resolveInjectionType(ObservationFact injection) {
        String fromTypeRef = injection.targetTypeRef() == null
                ? null
                : injection.targetTypeRef().raw();
        String fromTargetSymbol = injection.targetSymbol();
        String fromAttrs = firstNonBlank(
                firstString(injection.attrs(), "target_type"),
                firstString(injection.attrs(), "injection_type"),
                firstString(injection.attrs(), "provided_type")
        );

        return normalizeType(firstNonBlank(
                fromTypeRef,
                fromTargetSymbol,
                fromAttrs
        ));
    }

    private String resolveInternalTypeSymbol(
            String injectionType,
            boolean unresolvedType,
            Map<String, SymbolFact> symbolIndex
    ) {
        String exact = "type:" + injectionType;
        if (symbolIndex.containsKey(exact)) {
            return exact;
        }

        if (!unresolvedType) {
            return null;
        }

        List<String> simpleMatches = symbolIndex.keySet().stream()
                .filter(symbol -> symbol.startsWith("type:"))
                .filter(symbol -> simpleName(symbol).equals(simpleName(injectionType)))
                .toList();
        return simpleMatches.size() == 1
                ? simpleMatches.get(0)
                : null;
    }

    private List<String> explicitInjectionNames(Map<String, Object> attrs) {
        if (attrs == null || attrs.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<String> names = new LinkedHashSet<>();
        addStrings(names, attrs.get("qualifiers"));
        addStrings(names, attrs.get("qualifier"));
        addStrings(names, attrs.get("bean_names"));
        addStrings(names, attrs.get("bean_name"));
        addStrings(names, attrs.get("resource_name"));
        addStrings(names, attrs.get("name"));
        return List.copyOf(names);
    }

    private Map<String, Object> baseAttrs(
            ObservationFact injection,
            String injectionSiteSymbol,
            String injectionType
    ) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("semantic_kind", "dependency_injection");
        attrs.put("resolver", getClass().getSimpleName());
        attrs.put("injection_site_symbol", injectionSiteSymbol);
        attrs.put("injection_type", injectionType);
        attrs.put("injection_kind", injectionKind(injectionSiteSymbol));
        if (injection.note() != null && !injection.note().isBlank()) {
            attrs.put("observation_note", injection.note());
        }
        List<String> names = explicitInjectionNames(injection.attrs());
        if (!names.isEmpty()) {
            attrs.put("injection_qualifiers", names);
        }
        String parameterName = firstString(injection.attrs(), "parameter");
        if (parameterName != null) {
            attrs.put("parameter_name", parameterName);
        }
        return attrs;
    }

    private Destination destinationOf(ProviderCandidate provider) {
        if (provider.siteSymbol().startsWith("type:")) {
            return new Destination(provider.siteSymbol(), null);
        }

        if (!provider.beanNames().isEmpty()) {
            return new Destination(
                    null,
                    "bean:" + provider.beanNames().get(0)
            );
        }

        return new Destination(
                null,
                "type:" + provider.providedType()
        );
    }

    private List<String> mergeCandidateEvidence(
            List<String> injectionEvidence,
            List<ProviderCandidate> candidates
    ) {
        LinkedHashSet<String> ids = new LinkedHashSet<>(
                sanitizeEvidenceIds(injectionEvidence)
        );
        for (ProviderCandidate candidate : candidates) {
            ids.addAll(sanitizeEvidenceIds(
                    candidate.observation().evidenceIds()
            ));
        }
        return List.copyOf(ids);
    }

    private List<String> mergeEvidenceIds(
            List<String> left,
            List<String> right
    ) {
        LinkedHashSet<String> ids = new LinkedHashSet<>(
                sanitizeEvidenceIds(left)
        );
        ids.addAll(sanitizeEvidenceIds(right));
        return List.copyOf(ids);
    }

    private FactOriginKind mergeCandidateOrigins(
            FactOriginKind injectionOrigin,
            List<ProviderCandidate> candidates
    ) {
        FactOriginKind merged = injectionOrigin;
        for (ProviderCandidate candidate : candidates) {
            merged = mergeOrigin(
                    merged,
                    candidate.observation().origin()
            );
        }
        return merged == null ? FactOriginKind.OBSERVED : merged;
    }

    private FactOriginKind mergeOrigin(
            FactOriginKind left,
            FactOriginKind right
    ) {
        if (left == null) {
            return right == null ? FactOriginKind.OBSERVED : right;
        }
        if (right == null) {
            return left;
        }
        if (left == right) {
            return left;
        }
        if (left == FactOriginKind.OBSERVED) {
            return right;
        }
        if (right == FactOriginKind.OBSERVED) {
            return left;
        }
        if (left == FactOriginKind.AST_AND_BYTECODE
                || right == FactOriginKind.AST_AND_BYTECODE) {
            return FactOriginKind.AST_AND_BYTECODE;
        }
        if ((left == FactOriginKind.AST && right == FactOriginKind.BYTECODE)
                || (left == FactOriginKind.BYTECODE && right == FactOriginKind.AST)) {
            return FactOriginKind.AST_AND_BYTECODE;
        }
        return left;
    }

    private String injectionKind(String siteSymbol) {
        if (siteSymbol.startsWith("field:")) {
            return "field";
        }
        if (siteSymbol.startsWith("ctor:")) {
            return "constructor";
        }
        if (siteSymbol.startsWith("method:")) {
            return "method";
        }
        if (siteSymbol.startsWith("type:")) {
            return "type";
        }
        return "unknown";
    }

    private String inferBeanName(
            String siteSymbol,
            String providedType
    ) {
        if (siteSymbol.startsWith("method:")) {
            int hashIndex = siteSymbol.lastIndexOf('#');
            if (hashIndex >= 0 && hashIndex + 1 < siteSymbol.length()) {
                String methodName = siteSymbol.substring(hashIndex + 1);
                int paramsIndex = methodName.indexOf('(');
                if (paramsIndex >= 0) {
                    methodName = methodName.substring(0, paramsIndex);
                }
                return trimToNull(methodName);
            }
        }

        String simple = simpleName(providedType);
        if (simple == null) {
            return null;
        }
        if (simple.length() > 1
                && Character.isUpperCase(simple.charAt(0))
                && Character.isUpperCase(simple.charAt(1))) {
            return simple;
        }
        return Character.toLowerCase(simple.charAt(0))
                + simple.substring(1);
    }

    private String normalizeType(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }

        if (value.startsWith("type:")) {
            value = trimToNull(value.substring("type:".length()));
        }
        if (value != null && value.endsWith(".class")) {
            value = trimToNull(value.substring(0, value.length() - 6));
        }
        if (value != null) {
            int genericIndex = value.indexOf('<');
            if (genericIndex > 0) {
                value = trimToNull(value.substring(0, genericIndex));
            }
        }
        return value;
    }

    private void addType(Set<String> target, String rawType) {
        String normalized = normalizeType(rawType);
        if (normalized != null) {
            target.add(normalized);
        }
    }

    private Set<String> simpleTypeNames(Set<String> types) {
        if (types == null || types.isEmpty()) {
            return Set.of();
        }

        Set<String> simpleNames = new LinkedHashSet<>();
        for (String type : types) {
            String simpleName = ProviderCandidate.simpleTypeName(type);
            if (simpleName != null) {
                simpleNames.add(simpleName);
            }
        }
        return Set.copyOf(simpleNames);
    }

    private String simpleName(String raw) {
        String normalized = normalizeType(raw);
        if (normalized == null) {
            return null;
        }
        int dot = normalized.lastIndexOf('.');
        int nested = normalized.lastIndexOf('$');
        int separator = Math.max(dot, nested);
        return separator < 0
                ? normalized
                : normalized.substring(separator + 1);
    }

    private List<String> normalizeNames(List<String> rawNames) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String rawName : rawNames) {
            String name = trimToNull(rawName);
            if (name != null) {
                names.add(name);
            }
        }
        return List.copyOf(names);
    }

    private List<String> stringList(Object raw) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        addStrings(result, raw);
        return List.copyOf(result);
    }

    private void addStrings(Collection<String> target, Object raw) {
        if (raw == null) {
            return;
        }
        if (raw instanceof CharSequence sequence) {
            String value = trimToNull(sequence.toString());
            if (value != null) {
                target.add(value);
            }
            return;
        }
        if (raw instanceof Collection<?> collection) {
            for (Object item : collection) {
                addStrings(target, item);
            }
            return;
        }
        if (raw.getClass().isArray()) {
            int length = Array.getLength(raw);
            for (int index = 0; index < length; index++) {
                addStrings(target, Array.get(raw, index));
            }
            return;
        }
        String value = trimToNull(String.valueOf(raw));
        if (value != null) {
            target.add(value);
        }
    }

    private String firstString(Map<String, Object> attrs, String key) {
        if (attrs == null || key == null) {
            return null;
        }
        List<String> values = stringList(attrs.get(key));
        return values.isEmpty() ? null : values.get(0);
    }

    private String stringValue(Object value) {
        return value == null ? null : trimToNull(String.valueOf(value));
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String normalized = trimToNull(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private List<String> sanitizeEvidenceIds(List<String> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String value : source) {
            String id = trimToNull(value);
            if (id != null) {
                ids.add(id);
            }
        }
        return List.copyOf(ids);
    }

    private Map<String, Object> immutableAttrs(Map<String, Object> attrs) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(attrs));
    }

    private void putPolicyAttrs(
            Map<String, Object> attrs,
            ResolutionAssessment resolution,
            ConfidenceAssessment confidence
    ) {
        attrs.put("resolution_basis", resolution.basis().code());
        attrs.put("confidence_band", confidence.band().code());
        attrs.put("default_visible", confidence.defaultVisible());
    }

    private Double maxConfidenceHint(Double left, Double right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return Math.max(left, right);
    }

    private Double mergeCandidateConfidenceHint(
            Double injectionHint,
            List<ProviderCandidate> candidates
    ) {
        Double merged = injectionHint;
        for (ProviderCandidate candidate : candidates) {
            merged = maxConfidenceHint(
                    merged,
                    candidate.observation().confidenceHint()
            );
        }
        return merged;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record CandidateMatch(
            List<ProviderCandidate> candidates,
            boolean simpleNameMatch
    ) {
    }

    private record Destination(
            String symbol,
            String rawRef
    ) {
    }

    private record Selection(
            ProviderCandidate candidate,
            String strategy,
            boolean partial,
            String reason,
            int candidateCount
    ) {
        private static Selection resolved(
                ProviderCandidate candidate,
                String strategy,
                int candidateCount
        ) {
            return new Selection(
                    candidate,
                    strategy,
                    false,
                    null,
                    candidateCount
            );
        }

        private static Selection partial(
                ProviderCandidate candidate,
                String strategy,
                String reason,
                int candidateCount
        ) {
            return new Selection(
                    candidate,
                    strategy,
                    true,
                    reason,
                    candidateCount
            );
        }

        private static Selection none() {
            return none(null, null, 0);
        }

        private static Selection none(
                String strategy,
                String reason,
                int candidateCount
        ) {
            return new Selection(
                    null,
                    strategy,
                    false,
                    reason,
                    candidateCount
            );
        }
    }

    private record ProviderCandidate(
            ObservationFact observation,
            String siteSymbol,
            String providedType,
            Set<String> exposedTypes,
            Set<String> exposedSimpleTypes,
            List<String> beanNames,
            List<String> qualifiers,
            boolean primary,
            String providerKind
    ) {
        private boolean exposesExact(String injectionType) {
            return exposedTypes.contains(injectionType);
        }

        private boolean exposesSimple(String injectionType) {
            String expected = simpleTypeName(injectionType);
            if (expected == null) {
                return false;
            }
            return exposedSimpleTypes.contains(expected);
        }

        private boolean matchesAnyName(List<String> names) {
            return names.stream().anyMatch(this::matchesName);
        }

        private boolean matchesName(String name) {
            if (name == null) {
                return false;
            }
            return beanNames.contains(name) || qualifiers.contains(name);
        }

        private static String simpleTypeName(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String normalized = raw.startsWith("type:")
                    ? raw.substring("type:".length())
                    : raw;
            int dot = normalized.lastIndexOf('.');
            int nested = normalized.lastIndexOf('$');
            int separator = Math.max(dot, nested);
            return separator < 0
                    ? normalized
                    : normalized.substring(separator + 1);
        }
    }
}
