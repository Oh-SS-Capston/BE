package com.example.ossdoc.domain.graphstore.service.promotion;

import com.example.ossdoc.domain.extraction.enums.DerivationKind;
import com.example.ossdoc.domain.extraction.enums.FactOriginKind;
import com.example.ossdoc.domain.extraction.service.support.policy.ConfidenceAssessment;
import com.example.ossdoc.domain.extraction.service.support.policy.RelationConfidencePolicy;
import com.example.ossdoc.domain.extraction.service.support.policy.RelationPolicyInput;
import com.example.ossdoc.domain.extraction.service.support.policy.RelationResolutionPolicy;
import com.example.ossdoc.domain.extraction.service.support.policy.ResolutionAssessment;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedFactsDocument;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedObservationFact;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedRelationFact;
import com.example.ossdoc.domain.graphstore.model.normalized.NormalizedSymbolFact;
import com.example.ossdoc.domain.graphstore.model.promotion.ObservationPromotionCandidateGenerationResult;
import com.example.ossdoc.domain.graphstore.model.promotion.ObservationPromotionShadowCandidate;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * DI_INJECTION_SITE와 DI_PROVIDER Observation 및 normalized symbol을 사용해
 * INJECTS shadow Relation 후보를 생성한다.
 *
 * <p>Extraction DiObservationResolver는 직접 호출하지 않는다.</p>
 */
public final class DiShadowCandidateGenerator {

    private static final String INJECTION_KIND = "di_injection_site";
    private static final String PROVIDER_KIND = "di_provider";

    private DiShadowCandidateGenerator() {
    }

    public static ObservationPromotionCandidateGenerationResult generate(
            NormalizedFactsDocument facts,
            ObjectMapper objectMapper
    ) {
        if (facts == null) {
            return new ObservationPromotionCandidateGenerationResult(
                    0, List.of(), List.of()
            );
        }

        ObjectMapper mapper = objectMapper == null
                ? new ObjectMapper().findAndRegisterModules()
                : objectMapper;

        RelationResolutionPolicy resolutionPolicy =
                new RelationResolutionPolicy();
        RelationConfidencePolicy confidencePolicy =
                new RelationConfidencePolicy();

        List<NormalizedObservationFact> observations =
                facts.observations() == null ? List.of() : facts.observations();
        Map<String, NormalizedSymbolFact> symbols =
                symbolIndex(facts.symbols());
        List<ProviderCandidate> providers =
                providerCandidates(observations, symbols, mapper);

        List<ObservationPromotionShadowCandidate> candidates =
                new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int eligible = 0;

        for (int index = 0; index < observations.size(); index++) {
            NormalizedObservationFact injection = observations.get(index);
            if (injection == null
                    || !INJECTION_KIND.equals(normalizeCode(injection.kind()))) {
                continue;
            }

            eligible++;
            NormalizedRelationFact relation = resolveInjection(
                    index,
                    injection,
                    providers,
                    symbols,
                    mapper,
                    resolutionPolicy,
                    confidencePolicy,
                    warnings
            );

            if (relation != null) {
                candidates.add(new ObservationPromotionShadowCandidate(
                        index,
                        INJECTION_KIND,
                        relation
                ));
            }
        }

        return new ObservationPromotionCandidateGenerationResult(
                eligible,
                candidates,
                warnings
        );
    }

    private static NormalizedRelationFact resolveInjection(
            int index,
            NormalizedObservationFact injection,
            List<ProviderCandidate> providers,
            Map<String, NormalizedSymbolFact> symbols,
            ObjectMapper mapper,
            RelationResolutionPolicy resolutionPolicy,
            RelationConfidencePolicy confidencePolicy,
            List<String> warnings
    ) {
        String siteSymbol = trimToNull(injection.siteSymbol());
        if (siteSymbol == null) {
            warnings.add(
                    "DI_INJECTION_SITE observation has no siteSymbol "
                            + "and was skipped: index=" + index
            );
            return null;
        }

        Map<String, Object> injectionAttrs = attrs(injection, mapper);
        String sourceType = resolveOwnerTypeSymbol(
                siteSymbol,
                injectionAttrs,
                symbols
        );

        if (sourceType == null) {
            warnings.add(
                    "DI_INJECTION_SITE owner type could not be resolved: "
                            + siteSymbol
            );
            return null;
        }

        String injectionType = resolveInjectionType(
                injection,
                injectionAttrs
        );

        if (injectionType == null) {
            warnings.add(
                    "DI_INJECTION_SITE target type could not be resolved: "
                            + siteSymbol
            );
            return null;
        }

        CandidateMatch match = matchingCandidates(
                providers,
                injectionType,
                unresolved(injection.targetTypeRef())
        );

        List<String> explicitNames =
                explicitInjectionNames(injectionAttrs);
        String parameterName =
                firstString(injectionAttrs, "parameter");

        Selection selection = selectCandidate(
                match.candidates(),
                explicitNames,
                parameterName,
                match.simpleNameMatch()
        );

        if (selection.candidate() != null) {
            return resolvedRelation(
                    injection,
                    injectionAttrs,
                    sourceType,
                    siteSymbol,
                    injectionType,
                    selection,
                    resolutionPolicy,
                    confidencePolicy
            );
        }

        if (!match.candidates().isEmpty()) {
            return ambiguousRelation(
                    injection,
                    injectionAttrs,
                    sourceType,
                    siteSymbol,
                    injectionType,
                    match.candidates(),
                    explicitNames,
                    parameterName,
                    selection,
                    resolutionPolicy,
                    confidencePolicy
            );
        }

        return fallbackRelation(
                injection,
                injectionAttrs,
                sourceType,
                siteSymbol,
                injectionType,
                resolveInternalTypeSymbol(
                        injectionType,
                        unresolved(injection.targetTypeRef()),
                        symbols
                ),
                resolutionPolicy,
                confidencePolicy
        );
    }

    private static NormalizedRelationFact resolvedRelation(
            NormalizedObservationFact injection,
            Map<String, Object> injectionAttrs,
            String sourceType,
            String siteSymbol,
            String injectionType,
            Selection selection,
            RelationResolutionPolicy resolutionPolicy,
            RelationConfidencePolicy confidencePolicy
    ) {
        ProviderCandidate provider = selection.candidate();
        Destination destination = destinationOf(provider);
        List<String> evidenceIds = mergeEvidenceIds(
                injection.evidenceIds(),
                provider.observation().evidenceIds()
        );
        FactOriginKind origin = mergeOrigin(
                origin(injection.origin()),
                origin(provider.observation().origin())
        );

        RelationPolicyInput input = RelationPolicyInput.builder()
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
                .qualifierMatched("qualifier".equals(selection.strategy()))
                .primaryMatched("primary".equals(selection.strategy()))
                .evidencePresent(!evidenceIds.isEmpty())
                .sourceConfidenceHint(maxConfidence(
                        confidenceHint(injection),
                        confidenceHint(provider.observation())
                ))
                .build();

        ResolutionAssessment resolution = resolutionPolicy.assess(input);
        if (selection.reason() != null) {
            resolution = new ResolutionAssessment(
                    resolution.status(),
                    resolution.basis(),
                    selection.reason()
            );
        }

        ConfidenceAssessment confidence =
                confidencePolicy.assess(input, resolution);

        Map<String, Object> relationAttrs = baseAttrs(
                injection,
                injectionAttrs,
                siteSymbol,
                injectionType
        );
        relationAttrs.put("match_strategy", selection.strategy());
        relationAttrs.put("provider_symbol", provider.siteSymbol());
        relationAttrs.put("provided_type", provider.providedType());
        relationAttrs.put("provider_kind", provider.providerKind());
        relationAttrs.put("bean_names", provider.beanNames());
        relationAttrs.put("qualifiers", provider.qualifiers());
        relationAttrs.put("primary", provider.primary());
        relationAttrs.put("candidate_count", selection.candidateCount());
        putPolicyAttrs(relationAttrs, resolution, confidence);

        return relation(
                sourceType,
                destination.symbol(),
                destination.rawRef(),
                origin,
                resolution,
                confidence,
                relationAttrs,
                evidenceIds
        );
    }

    private static NormalizedRelationFact ambiguousRelation(
            NormalizedObservationFact injection,
            Map<String, Object> injectionAttrs,
            String sourceType,
            String siteSymbol,
            String injectionType,
            List<ProviderCandidate> candidates,
            List<String> explicitNames,
            String parameterName,
            Selection selection,
            RelationResolutionPolicy resolutionPolicy,
            RelationConfidencePolicy confidencePolicy
    ) {
        String strategy = selection.strategy() == null
                ? "ambiguous"
                : selection.strategy();
        String reason = selection.reason() == null
                ? "Multiple DI providers matched the injection type"
                : selection.reason();

        List<String> evidenceIds = mergeCandidateEvidence(
                injection.evidenceIds(),
                candidates
        );
        FactOriginKind origin = mergeCandidateOrigins(
                origin(injection.origin()),
                candidates
        );

        RelationPolicyInput input = RelationPolicyInput.builder()
                .origin(origin)
                .derivation(DerivationKind.DERIVED)
                .targetReferenceKnown(true)
                .targetReferenceAuthoritative(false)
                .inferred(false)
                .candidateCount(candidates.size())
                .evidencePresent(!evidenceIds.isEmpty())
                .sourceConfidenceHint(mergeCandidateConfidence(
                        confidenceHint(injection),
                        candidates
                ))
                .build();

        ResolutionAssessment assessed = resolutionPolicy.assess(input);
        ResolutionAssessment resolution = new ResolutionAssessment(
                assessed.status(),
                assessed.basis(),
                reason
        );
        ConfidenceAssessment confidence =
                confidencePolicy.assess(input, resolution);

        Map<String, Object> relationAttrs = baseAttrs(
                injection,
                injectionAttrs,
                siteSymbol,
                injectionType
        );
        relationAttrs.put("match_strategy", strategy);
        relationAttrs.put("candidate_count", candidates.size());
        relationAttrs.put(
                "candidate_provider_symbols",
                candidates.stream()
                        .map(ProviderCandidate::siteSymbol)
                        .toList()
        );
        if (!explicitNames.isEmpty()) {
            relationAttrs.put("injection_qualifiers", explicitNames);
        }
        if (parameterName != null) {
            relationAttrs.put("parameter_name", parameterName);
        }
        putPolicyAttrs(relationAttrs, resolution, confidence);

        return relation(
                sourceType,
                null,
                "type:" + injectionType,
                origin,
                resolution,
                confidence,
                relationAttrs,
                evidenceIds
        );
    }

    private static NormalizedRelationFact fallbackRelation(
            NormalizedObservationFact injection,
            Map<String, Object> injectionAttrs,
            String sourceType,
            String siteSymbol,
            String injectionType,
            String internalTypeSymbol,
            RelationResolutionPolicy resolutionPolicy,
            RelationConfidencePolicy confidencePolicy
    ) {
        List<String> evidenceIds =
                sanitizeEvidenceIds(injection.evidenceIds());
        FactOriginKind origin = origin(injection.origin());
        String reason = internalTypeSymbol == null
                ? "No DI provider observation matched the injection type"
                : "Internal type found but DI provider observation was absent";

        RelationPolicyInput input = RelationPolicyInput.builder()
                .origin(origin)
                .derivation(DerivationKind.DERIVED)
                .targetSymbolResolved(internalTypeSymbol != null)
                .targetReferenceKnown(true)
                .targetReferenceAuthoritative(false)
                .inferred(true)
                .candidateCount(0)
                .evidencePresent(!evidenceIds.isEmpty())
                .sourceConfidenceHint(confidenceHint(injection))
                .build();

        ResolutionAssessment assessed = resolutionPolicy.assess(input);
        ResolutionAssessment resolution = new ResolutionAssessment(
                assessed.status(),
                assessed.basis(),
                reason
        );
        ConfidenceAssessment confidence =
                confidencePolicy.assess(input, resolution);

        Map<String, Object> relationAttrs = baseAttrs(
                injection,
                injectionAttrs,
                siteSymbol,
                injectionType
        );
        relationAttrs.put(
                "match_strategy",
                internalTypeSymbol == null
                        ? "unresolved_provider"
                        : "internal_type_fallback"
        );
        relationAttrs.put("candidate_count", 0);
        putPolicyAttrs(relationAttrs, resolution, confidence);

        return relation(
                sourceType,
                internalTypeSymbol,
                internalTypeSymbol == null
                        ? "type:" + injectionType
                        : null,
                origin,
                resolution,
                confidence,
                relationAttrs,
                evidenceIds
        );
    }

    private static NormalizedRelationFact relation(
            String sourceType,
            String destinationSymbol,
            String destinationRawRef,
            FactOriginKind origin,
            ResolutionAssessment resolution,
            ConfidenceAssessment confidence,
            Map<String, Object> attrs,
            List<String> evidenceIds
    ) {
        return new NormalizedRelationFact(
                "injects",
                sourceType,
                destinationSymbol,
                destinationRawRef,
                origin.code(),
                "derived",
                resolution.status().code(),
                resolution.reason(),
                null,
                BigDecimal.valueOf(confidence.value()),
                Collections.unmodifiableMap(new LinkedHashMap<>(attrs)),
                evidenceIds
        );
    }

    private static Selection selectCandidate(
            List<ProviderCandidate> candidates,
            List<String> explicitNames,
            String parameterName,
            boolean simpleNameMatch
    ) {
        if (candidates.isEmpty()) {
            return Selection.none();
        }

        List<ProviderCandidate> narrowed = candidates;

        if (!explicitNames.isEmpty()) {
            List<ProviderCandidate> qualified = narrowed.stream()
                    .filter(candidate ->
                            candidate.matchesAnyName(explicitNames))
                    .toList();

            if (qualified.size() == 1) {
                return Selection.resolved(
                        qualified.get(0),
                        "qualifier",
                        candidates.size()
                );
            }
            if (qualified.size() > 1) {
                narrowed = qualified;
            } else {
                return Selection.none(
                        "qualifier_mismatch",
                        "Injection qualifier did not match any DI provider candidate",
                        candidates.size()
                );
            }
        }

        if (parameterName != null && narrowed.size() > 1) {
            List<ProviderCandidate> named = narrowed.stream()
                    .filter(candidate ->
                            candidate.matchesName(parameterName))
                    .toList();

            if (named.size() == 1) {
                return Selection.partial(
                        named.get(0),
                        "parameter_name",
                        "Provider selected by injection parameter name",
                        candidates.size()
                );
            }
            if (named.size() > 1) {
                narrowed = named;
            }
        }

        if (narrowed.size() == 1) {
            ProviderCandidate candidate = narrowed.get(0);
            return simpleNameMatch
                    ? Selection.partial(
                            candidate,
                            "simple_type_name",
                            "Provider matched by unresolved simple type name",
                            1
                    )
                    : Selection.resolved(
                            candidate,
                            "exact_type",
                            1
                    );
        }

        List<ProviderCandidate> primary = narrowed.stream()
                .filter(ProviderCandidate::primary)
                .toList();

        if (primary.size() == 1) {
            return Selection.resolved(
                    primary.get(0),
                    "primary",
                    narrowed.size()
            );
        }

        return Selection.none(
                "ambiguous",
                "Multiple DI providers matched the injection type",
                narrowed.size()
        );
    }

    private static CandidateMatch matchingCandidates(
            List<ProviderCandidate> providers,
            String injectionType,
            boolean unresolvedType
    ) {
        List<ProviderCandidate> exact = providers.stream()
                .filter(candidate ->
                        candidate.exposesExact(injectionType))
                .toList();

        if (!exact.isEmpty()) {
            return new CandidateMatch(exact, false);
        }
        if (!unresolvedType) {
            return new CandidateMatch(List.of(), false);
        }

        List<ProviderCandidate> simple = providers.stream()
                .filter(candidate ->
                        candidate.exposesSimple(injectionType))
                .toList();

        return new CandidateMatch(simple, !simple.isEmpty());
    }

    private static List<ProviderCandidate> providerCandidates(
            List<NormalizedObservationFact> observations,
            Map<String, NormalizedSymbolFact> symbols,
            ObjectMapper mapper
    ) {
        List<ProviderCandidate> result = new ArrayList<>();

        for (NormalizedObservationFact provider : observations) {
            if (provider == null
                    || !PROVIDER_KIND.equals(
                    normalizeCode(provider.kind()))) {
                continue;
            }

            ProviderCandidate candidate =
                    toProviderCandidate(provider, symbols, mapper);
            if (candidate != null) {
                result.add(candidate);
            }
        }

        return List.copyOf(result);
    }

    private static ProviderCandidate toProviderCandidate(
            NormalizedObservationFact provider,
            Map<String, NormalizedSymbolFact> symbols,
            ObjectMapper mapper
    ) {
        String siteSymbol = trimToNull(provider.siteSymbol());
        if (siteSymbol == null) {
            return null;
        }

        Map<String, Object> providerAttrs = attrs(provider, mapper);
        String providedType = normalizeType(firstNonBlank(
                stringValue(providerAttrs.get("provided_type")),
                firstNonBlank(
                        typeRefRaw(provider.targetTypeRef()),
                        siteSymbol.startsWith("type:")
                                ? siteSymbol.substring("type:".length())
                                : null
                )
        ));

        LinkedHashSet<String> exposedTypes = new LinkedHashSet<>();
        addType(exposedTypes, providedType);

        NormalizedSymbolFact providerSymbol = symbols.get(siteSymbol);
        if (providerSymbol != null) {
            addType(exposedTypes, providerSymbol.qualifiedName());
            addType(exposedTypes, providerSymbol.superclassTypeRef());

            if (providerSymbol.interfaceTypeRefs() != null) {
                for (String interfaceType
                        : providerSymbol.interfaceTypeRefs()) {
                    addType(exposedTypes, interfaceType);
                }
            }
        }

        if (exposedTypes.isEmpty()) {
            return null;
        }

        List<String> beanNames = normalizeNames(
                stringList(providerAttrs.get("bean_names"))
        );
        if (beanNames.isEmpty()) {
            String inferred = inferBeanName(siteSymbol, providedType);
            if (inferred != null) {
                beanNames = List.of(inferred);
            }
        }

        return new ProviderCandidate(
                provider,
                siteSymbol,
                providedType,
                exposedTypes,
                beanNames,
                normalizeNames(
                        stringList(providerAttrs.get("qualifiers"))
                ),
                booleanValue(providerAttrs.get("primary")),
                firstNonBlank(
                        stringValue(providerAttrs.get("provider_kind")),
                        siteSymbol.startsWith("method:")
                                ? "provider_method"
                                : "component_type"
                )
        );
    }

    private static Map<String, NormalizedSymbolFact> symbolIndex(
            List<NormalizedSymbolFact> symbols
    ) {
        if (symbols == null || symbols.isEmpty()) {
            return Map.of();
        }

        LinkedHashMap<String, NormalizedSymbolFact> result =
                new LinkedHashMap<>();

        for (NormalizedSymbolFact symbol : symbols) {
            if (symbol != null
                    && trimToNull(symbol.symbol()) != null) {
                result.put(symbol.symbol(), symbol);
            }
        }

        return Collections.unmodifiableMap(result);
    }

    private static String resolveOwnerTypeSymbol(
            String siteSymbol,
            Map<String, Object> attrs,
            Map<String, NormalizedSymbolFact> symbols
    ) {
        NormalizedSymbolFact site = symbols.get(siteSymbol);
        if (site != null) {
            String owner = trimToNull(site.ownerTypeSymbol());
            if (owner != null) {
                return owner;
            }
            if (siteSymbol.startsWith("type:")) {
                return siteSymbol;
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

        if (siteSymbol.startsWith("type:")) {
            return siteSymbol;
        }

        int colon = siteSymbol.indexOf(':');
        int hash = siteSymbol.indexOf('#');

        if (colon >= 0 && hash > colon + 1) {
            String owner = normalizeType(
                    siteSymbol.substring(colon + 1, hash)
            );
            return owner == null ? null : "type:" + owner;
        }

        return null;
    }

    private static String resolveInjectionType(
            NormalizedObservationFact injection,
            Map<String, Object> attrs
    ) {
        return normalizeType(firstNonBlank(
                typeRefRaw(injection.targetTypeRef()),
                firstNonBlank(
                        injection.targetSymbol(),
                        firstNonBlank(
                                firstString(attrs, "target_type"),
                                firstNonBlank(
                                        firstString(attrs, "injection_type"),
                                        firstString(attrs, "provided_type")
                                )
                        )
                )
        ));
    }

    private static String resolveInternalTypeSymbol(
            String injectionType,
            boolean unresolvedType,
            Map<String, NormalizedSymbolFact> symbols
    ) {
        String exact = "type:" + injectionType;
        if (symbols.containsKey(exact)) {
            return exact;
        }
        if (!unresolvedType) {
            return null;
        }

        List<String> matches = symbols.keySet().stream()
                .filter(symbol -> symbol.startsWith("type:"))
                .filter(symbol ->
                        simpleName(symbol).equals(simpleName(injectionType)))
                .toList();

        return matches.size() == 1 ? matches.get(0) : null;
    }

    private static List<String> explicitInjectionNames(
            Map<String, Object> attrs
    ) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        addStrings(names, attrs.get("qualifiers"));
        addStrings(names, attrs.get("qualifier"));
        addStrings(names, attrs.get("bean_names"));
        addStrings(names, attrs.get("bean_name"));
        addStrings(names, attrs.get("resource_name"));
        addStrings(names, attrs.get("name"));
        return List.copyOf(names);
    }

    private static Map<String, Object> baseAttrs(
            NormalizedObservationFact injection,
            Map<String, Object> injectionAttrs,
            String siteSymbol,
            String injectionType
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("semantic_kind", "dependency_injection");
        result.put("resolver", "DiObservationResolver");
        result.put("injection_site_symbol", siteSymbol);
        result.put("injection_type", injectionType);
        result.put("injection_kind", injectionKind(siteSymbol));

        String note = trimToNull(injection.note());
        if (note != null) {
            result.put("observation_note", note);
        }

        List<String> names =
                explicitInjectionNames(injectionAttrs);
        if (!names.isEmpty()) {
            result.put("injection_qualifiers", names);
        }

        String parameter =
                firstString(injectionAttrs, "parameter");
        if (parameter != null) {
            result.put("parameter_name", parameter);
        }

        return result;
    }

    private static Destination destinationOf(
            ProviderCandidate provider
    ) {
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

    private static List<String> mergeCandidateEvidence(
            List<String> injectionEvidence,
            List<ProviderCandidate> candidates
    ) {
        LinkedHashSet<String> result = new LinkedHashSet<>(
                sanitizeEvidenceIds(injectionEvidence)
        );
        for (ProviderCandidate candidate : candidates) {
            result.addAll(sanitizeEvidenceIds(
                    candidate.observation().evidenceIds()
            ));
        }
        return List.copyOf(result);
    }

    private static List<String> mergeEvidenceIds(
            List<String> left,
            List<String> right
    ) {
        LinkedHashSet<String> result =
                new LinkedHashSet<>(sanitizeEvidenceIds(left));
        result.addAll(sanitizeEvidenceIds(right));
        return List.copyOf(result);
    }

    private static FactOriginKind mergeCandidateOrigins(
            FactOriginKind injectionOrigin,
            List<ProviderCandidate> candidates
    ) {
        FactOriginKind result = injectionOrigin;
        for (ProviderCandidate candidate : candidates) {
            result = mergeOrigin(
                    result,
                    origin(candidate.observation().origin())
            );
        }
        return result == null ? FactOriginKind.OBSERVED : result;
    }

    private static FactOriginKind mergeOrigin(
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
                || right == FactOriginKind.AST_AND_BYTECODE
                || (left == FactOriginKind.AST
                && right == FactOriginKind.BYTECODE)
                || (left == FactOriginKind.BYTECODE
                && right == FactOriginKind.AST)) {
            return FactOriginKind.AST_AND_BYTECODE;
        }
        return left;
    }

    private static String injectionKind(String symbol) {
        if (symbol.startsWith("field:")) return "field";
        if (symbol.startsWith("ctor:")) return "constructor";
        if (symbol.startsWith("method:")) return "method";
        if (symbol.startsWith("type:")) return "type";
        return "unknown";
    }

    private static String inferBeanName(
            String siteSymbol,
            String providedType
    ) {
        if (siteSymbol.startsWith("method:")) {
            int hash = siteSymbol.lastIndexOf('#');
            if (hash >= 0 && hash + 1 < siteSymbol.length()) {
                String name = siteSymbol.substring(hash + 1);
                int params = name.indexOf('(');
                if (params >= 0) {
                    name = name.substring(0, params);
                }
                return trimToNull(name);
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

    private static String normalizeType(String raw) {
        String value = trimToNull(raw);
        if (value == null) return null;

        if (value.startsWith("type:")) {
            value = trimToNull(value.substring("type:".length()));
        }
        if (value != null && value.endsWith(".class")) {
            value = trimToNull(value.substring(0, value.length() - 6));
        }
        if (value != null) {
            int generic = value.indexOf('<');
            if (generic > 0) {
                value = trimToNull(value.substring(0, generic));
            }
        }
        return value;
    }

    private static void addType(Set<String> target, String raw) {
        String normalized = normalizeType(raw);
        if (normalized != null) target.add(normalized);
    }

    private static String simpleName(String raw) {
        String value = normalizeType(raw);
        if (value == null) return null;
        int separator = Math.max(
                value.lastIndexOf('.'),
                value.lastIndexOf('$')
        );
        return separator < 0
                ? value
                : value.substring(separator + 1);
    }

    private static List<String> normalizeNames(List<String> raw) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : raw) {
            String normalized = trimToNull(value);
            if (normalized != null) result.add(normalized);
        }
        return List.copyOf(result);
    }

    private static Map<String, Object> attrs(
            NormalizedObservationFact observation,
            ObjectMapper mapper
    ) {
        if (observation == null
                || observation.attrs() == null
                || observation.attrs().isNull()) {
            return new LinkedHashMap<>();
        }

        Map<String, Object> converted = mapper.convertValue(
                observation.attrs(),
                new TypeReference<Map<String, Object>>() {}
        );

        return converted == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(converted);
    }

    private static List<String> stringList(Object raw) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        addStrings(result, raw);
        return List.copyOf(result);
    }

    private static void addStrings(
            Collection<String> target,
            Object raw
    ) {
        if (raw == null) return;

        if (raw instanceof CharSequence sequence) {
            String value = trimToNull(sequence.toString());
            if (value != null) target.add(value);
            return;
        }

        if (raw instanceof Collection<?> collection) {
            collection.forEach(item -> addStrings(target, item));
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
        if (value != null) target.add(value);
    }

    private static String firstString(
            Map<String, Object> attrs,
            String key
    ) {
        List<String> values = stringList(attrs.get(key));
        return values.isEmpty() ? null : values.get(0);
    }

    private static String stringValue(Object value) {
        return value == null
                ? null
                : trimToNull(String.valueOf(value));
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) return bool;
        return value != null
                && Boolean.parseBoolean(String.valueOf(value));
    }

    private static boolean unresolved(JsonNode typeRef) {
        if (typeRef == null || typeRef.isNull()) return false;
        JsonNode value = typeRef.get("unresolved");
        return value != null && value.asBoolean(false);
    }

    private static String typeRefRaw(JsonNode typeRef) {
        if (typeRef == null || typeRef.isNull()) return null;
        if (typeRef.isTextual()) return trimToNull(typeRef.asText());

        JsonNode raw = typeRef.get("raw");
        return raw == null || raw.isNull()
                ? null
                : trimToNull(raw.asText());
    }

    private static List<String> sanitizeEvidenceIds(
            List<String> source
    ) {
        if (source == null || source.isEmpty()) return List.of();

        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String id : source) {
            String value = trimToNull(id);
            if (value != null) result.add(value);
        }
        return List.copyOf(result);
    }

    private static Double mergeCandidateConfidence(
            Double injectionConfidence,
            List<ProviderCandidate> candidates
    ) {
        Double result = injectionConfidence;
        for (ProviderCandidate candidate : candidates) {
            result = maxConfidence(
                    result,
                    confidenceHint(candidate.observation())
            );
        }
        return result;
    }

    private static Double maxConfidence(Double left, Double right) {
        if (left == null) return right;
        if (right == null) return left;
        return Math.max(left, right);
    }

    private static Double confidenceHint(
            NormalizedObservationFact observation
    ) {
        return observation == null
                || observation.confidenceHint() == null
                ? null
                : observation.confidenceHint().doubleValue();
    }

    private static void putPolicyAttrs(
            Map<String, Object> attrs,
            ResolutionAssessment resolution,
            ConfidenceAssessment confidence
    ) {
        attrs.put("resolution_basis", resolution.basis().code());
        attrs.put("confidence_band", confidence.band().code());
        attrs.put("default_visible", confidence.defaultVisible());
    }

    private static FactOriginKind origin(String raw) {
        String value = normalizeCode(raw);
        if (value == null) return FactOriginKind.OBSERVED;

        return switch (value) {
            case "ast" -> FactOriginKind.AST;
            case "bytecode" -> FactOriginKind.BYTECODE;
            case "ast_and_bytecode", "merged" ->
                    FactOriginKind.AST_AND_BYTECODE;
            case "resource" -> FactOriginKind.RESOURCE;
            case "observed" -> FactOriginKind.OBSERVED;
            default -> FactOriginKind.OBSERVED;
        };
    }

    private static String firstNonBlank(String left, String right) {
        String value = trimToNull(left);
        return value != null ? value : trimToNull(right);
    }

    private static String normalizeCode(String value) {
        String normalized = trimToNull(value);
        return normalized == null
                ? null
                : normalized.toLowerCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank()
                ? null
                : value.trim();
    }

    private record Destination(String symbol, String rawRef) {
    }

    private record CandidateMatch(
            List<ProviderCandidate> candidates,
            boolean simpleNameMatch
    ) {
        private CandidateMatch {
            candidates = candidates == null
                    ? List.of()
                    : List.copyOf(candidates);
        }
    }

    private record Selection(
            ProviderCandidate candidate,
            String strategy,
            String reason,
            boolean partial,
            int candidateCount
    ) {
        private static Selection none() {
            return new Selection(null, null, null, false, 0);
        }

        private static Selection none(
                String strategy,
                String reason,
                int candidateCount
        ) {
            return new Selection(
                    null, strategy, reason, false, candidateCount
            );
        }

        private static Selection resolved(
                ProviderCandidate candidate,
                String strategy,
                int candidateCount
        ) {
            return new Selection(
                    candidate, strategy, null, false, candidateCount
            );
        }

        private static Selection partial(
                ProviderCandidate candidate,
                String strategy,
                String reason,
                int candidateCount
        ) {
            return new Selection(
                    candidate, strategy, reason, true, candidateCount
            );
        }
    }

    private record ProviderCandidate(
            NormalizedObservationFact observation,
            String siteSymbol,
            String providedType,
            Set<String> exposedTypes,
            List<String> beanNames,
            List<String> qualifiers,
            boolean primary,
            String providerKind
    ) {
        private ProviderCandidate {
            exposedTypes = exposedTypes == null
                    ? Set.of()
                    : Set.copyOf(exposedTypes);
            beanNames = beanNames == null
                    ? List.of()
                    : List.copyOf(beanNames);
            qualifiers = qualifiers == null
                    ? List.of()
                    : List.copyOf(qualifiers);
        }

        private boolean exposesExact(String injectionType) {
            return exposedTypes.contains(injectionType);
        }

        private boolean exposesSimple(String injectionType) {
            String expected = simpleTypeName(injectionType);
            return expected != null
                    && exposedTypes.stream()
                    .map(ProviderCandidate::simpleTypeName)
                    .anyMatch(expected::equals);
        }

        private boolean matchesAnyName(List<String> names) {
            return names.stream().anyMatch(this::matchesName);
        }

        private boolean matchesName(String name) {
            return name != null
                    && (beanNames.contains(name)
                    || qualifiers.contains(name));
        }

        private static String simpleTypeName(String raw) {
            if (raw == null || raw.isBlank()) return null;

            String value = raw.startsWith("type:")
                    ? raw.substring("type:".length())
                    : raw;
            int separator = Math.max(
                    value.lastIndexOf('.'),
                    value.lastIndexOf('$')
            );
            return separator < 0
                    ? value
                    : value.substring(separator + 1);
        }
    }
}
