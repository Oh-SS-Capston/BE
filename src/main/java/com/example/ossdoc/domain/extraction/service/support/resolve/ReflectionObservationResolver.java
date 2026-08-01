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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** REFLECTION_SITE observation을 타입·메서드·필드·생성자 의미 관계로 승격한다. */
@Component
public class ReflectionObservationResolver implements ObservationRelationResolver {

    private static final String UNKNOWN_TYPE = "<unresolved-reflection-type>";
    private static final String UNKNOWN_MEMBER = "<unresolved-reflection-member>";

    private RelationResolutionPolicy resolutionPolicy;
    private RelationConfidencePolicy confidencePolicy;

    public ReflectionObservationResolver() {
        this.resolutionPolicy = new RelationResolutionPolicy();
        this.confidencePolicy = new RelationConfidencePolicy();
    }

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
        return Set.of(ObservationKind.REFLECTION_SITE);
    }

    @Override
    public int order() {
        return 700;
    }

    @Override
    public ObservationResolutionResult resolve(
            ObservationResolutionContext context
    ) {
        if (context == null) {
            return new ObservationResolutionResult(
                    List.of(),
                    List.of("Reflection resolver received a null context")
            );
        }

        ObservationTable table = context.observations();
        if (table == null || table.reflectionSites() == null) {
            return ObservationResolutionResult.empty();
        }

        List<RelationFact> relations = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (ObservationFact observation : table.reflectionSites()) {
            resolveObservation(
                    observation,
                    context.symbols(),
                    relations,
                    warnings
            );
        }

        return new ObservationResolutionResult(relations, warnings);
    }

    private void resolveObservation(
            ObservationFact observation,
            SymbolTable symbols,
            List<RelationFact> relations,
            List<String> warnings
    ) {
        if (observation == null) {
            return;
        }

        String siteSymbol = trimToNull(observation.siteSymbol());
        if (siteSymbol == null) {
            warnings.add("REFLECTION_SITE observation has no siteSymbol and was skipped");
            return;
        }

        Map<String, Object> sourceAttrs = observation.attrs() == null
                ? Map.of()
                : observation.attrs();
        String apiMethod = firstString(sourceAttrs, "api_method", "method");
        ReflectionKind reflectionKind = reflectionKind(
                firstString(sourceAttrs, "reflection_kind"),
                apiMethod,
                firstString(sourceAttrs, "owner", "api_owner")
        );

        String targetType = targetType(observation, sourceAttrs);
        String memberName = firstString(sourceAttrs, "member_name");
        List<String> parameterTypes = stringList(sourceAttrs.get("parameter_types"));

        ResolutionTarget target = switch (reflectionKind) {
            case TYPE -> resolveTypeTarget(targetType, symbols);
            case METHOD -> resolveMethodTarget(
                    targetType,
                    memberName,
                    parameterTypes,
                    symbols
            );
            case FIELD -> resolveFieldTarget(
                    targetType,
                    memberName,
                    symbols
            );
            case CONSTRUCTOR -> resolveConstructorTarget(
                    targetType,
                    parameterTypes,
                    symbols
            );
            case UNKNOWN -> ResolutionTarget.partial(
                    RelationKind.REFLECTS_TYPE,
                    "reflection:" + (apiMethod == null ? "unknown" : apiMethod),
                    "Reflection API kind could not be classified",
                    "unknown_api",
                    apiMethod != null,
                    0
            );
        };

        List<String> evidenceIds = sanitizeEvidenceIds(
                observation.evidenceIds()
        );
        FactOriginKind origin = observation.origin() == null
                ? FactOriginKind.OBSERVED
                : observation.origin();

        RelationPolicyInput policyInput = RelationPolicyInput.builder()
                .origin(origin)
                .derivation(DerivationKind.DERIVED)
                .targetSymbolResolved(target.symbolResolved())
                .targetReferenceKnown(target.referenceKnown())
                .targetReferenceAuthoritative(
                        target.referenceAuthoritative()
                )
                .inferred(target.inferred())
                .candidateCount(target.candidateCount())
                .evidencePresent(!evidenceIds.isEmpty())
                .sourceConfidenceHint(observation.confidenceHint())
                .build();

        ResolutionAssessment policyResolution =
                resolutionPolicy.assess(policyInput);
        ResolutionAssessment resolution = target.reason() == null
                || policyResolution.status()
                == com.example.ossdoc.domain.extraction.enums.ResolutionStatus.RESOLVED
                ? policyResolution
                : new ResolutionAssessment(
                        policyResolution.status(),
                        policyResolution.basis(),
                        target.reason()
                );
        ConfidenceAssessment confidence = confidencePolicy.assess(
                policyInput,
                resolution
        );

        Map<String, Object> attrs = copyNonNullAttrs(sourceAttrs);
        attrs.put("resolver", getClass().getSimpleName());
        attrs.put("semantic_kind", "reflection_reference");
        attrs.put("reflection_kind", reflectionKind.code);
        attrs.put("match_strategy", target.matchStrategy());
        attrs.put(
                "target_resolution",
                resolution.status().name().toLowerCase(Locale.ROOT)
        );
        attrs.put("resolution_basis", resolution.basis().code());
        attrs.put("confidence_band", confidence.band().code());
        attrs.put("default_visible", confidence.defaultVisible());
        if (target.candidateCount() > 1) {
            attrs.put("candidate_count", target.candidateCount());
        }
        if (target.inferred()) {
            attrs.put("inferred_match", true);
        }
        if (apiMethod != null) {
            attrs.put("api_method", apiMethod);
        }
        if (targetType != null) {
            attrs.put("target_type", targetType);
        }
        if (memberName != null) {
            attrs.put("member_name", memberName);
        }

        RelationFact.RelationFactBuilder builder = RelationFact.builder()
                .kind(target.relationKind())
                .srcSymbol(siteSymbol)
                .evidenceIds(evidenceIds)
                .resolution(resolution.toRelationResolution())
                .origin(origin)
                .derivation(DerivationKind.DERIVED)
                .confidenceHint(confidence.value())
                .attrs(immutableAttrs(attrs));

        if (target.dstSymbol() != null) {
            builder.dstSymbol(target.dstSymbol());
        } else {
            builder.dstRawRef(target.dstRawRef());
        }

        relations.add(builder.build());
    }

    private ResolutionTarget resolveTypeTarget(
            String rawTargetType,
            SymbolTable symbols
    ) {
        String normalizedType = normalizeRawType(rawTargetType);
        if (normalizedType == null) {
            return ResolutionTarget.partial(
                    RelationKind.REFLECTS_TYPE,
                    "type:" + UNKNOWN_TYPE,
                    "Reflection target type could not be statically determined",
                    "unresolved_type",
                    false,
                    0
            );
        }

        List<SymbolFact> matches = matchingTypes(symbols, normalizedType);
        if (matches.size() == 1) {
            return ResolutionTarget.resolved(
                    RelationKind.REFLECTS_TYPE,
                    matches.get(0).symbol(),
                    "exact_type_symbol"
            );
        }
        if (matches.size() > 1) {
            return ResolutionTarget.partial(
                    RelationKind.REFLECTS_TYPE,
                    "type:" + normalizedType,
                    "Multiple extracted types matched the reflection target",
                    "ambiguous_type",
                    true,
                    matches.size()
            );
        }

        return ResolutionTarget.partial(
                RelationKind.REFLECTS_TYPE,
                "type:" + normalizedType,
                "Reflection target type is statically known but not present in extracted symbols",
                "static_type_raw_ref",
                true,
                0
        );
    }

    private ResolutionTarget resolveMethodTarget(
            String rawTargetType,
            String memberName,
            List<String> parameterTypes,
            SymbolTable symbols
    ) {
        String normalizedType = normalizeRawType(rawTargetType);
        String normalizedMember = trimToNull(memberName);
        if (normalizedType == null || normalizedMember == null) {
            return ResolutionTarget.partial(
                    RelationKind.REFLECTS_METHOD,
                    methodRawRef(normalizedType, normalizedMember, parameterTypes),
                    "Reflection method owner or member name could not be statically determined",
                    "unresolved_method",
                    normalizedType != null
                            || normalizedMember != null
                            || (parameterTypes != null && !parameterTypes.isEmpty()),
                    0
            );
        }

        List<SymbolFact> matches = matchingMembers(
                symbols == null ? null : symbols.methods(),
                normalizedType,
                normalizedMember,
                parameterTypes
        );
        if (matches.size() == 1) {
            return ResolutionTarget.resolved(
                    RelationKind.REFLECTS_METHOD,
                    matches.get(0).symbol(),
                    parameterTypes.isEmpty()
                            ? "exact_method_name"
                            : "exact_method_signature"
            );
        }
        if (matches.size() > 1) {
            return ResolutionTarget.partial(
                    RelationKind.REFLECTS_METHOD,
                    methodRawRef(normalizedType, normalizedMember, parameterTypes),
                    "Multiple reflected method candidates matched the extracted symbols",
                    "ambiguous_method_overload",
                    true,
                    matches.size()
            );
        }

        return ResolutionTarget.partial(
                RelationKind.REFLECTS_METHOD,
                methodRawRef(normalizedType, normalizedMember, parameterTypes),
                "Reflected method was statically described but no extracted symbol matched",
                "static_method_raw_ref",
                true,
                0
        );
    }

    private ResolutionTarget resolveFieldTarget(
            String rawTargetType,
            String memberName,
            SymbolTable symbols
    ) {
        String normalizedType = normalizeRawType(rawTargetType);
        String normalizedMember = trimToNull(memberName);
        if (normalizedType == null || normalizedMember == null) {
            return ResolutionTarget.partial(
                    RelationKind.REFLECTS_FIELD,
                    fieldRawRef(normalizedType, normalizedMember),
                    "Reflection field owner or member name could not be statically determined",
                    "unresolved_field",
                    normalizedType != null || normalizedMember != null,
                    0
            );
        }

        List<SymbolFact> matches = matchingMembers(
                symbols == null ? null : symbols.fields(),
                normalizedType,
                normalizedMember,
                List.of()
        );
        if (matches.size() == 1) {
            return ResolutionTarget.resolved(
                    RelationKind.REFLECTS_FIELD,
                    matches.get(0).symbol(),
                    "exact_field_symbol"
            );
        }
        if (matches.size() > 1) {
            return ResolutionTarget.partial(
                    RelationKind.REFLECTS_FIELD,
                    fieldRawRef(normalizedType, normalizedMember),
                    "Multiple reflected field candidates matched the extracted symbols",
                    "ambiguous_field",
                    true,
                    matches.size()
            );
        }

        return ResolutionTarget.partial(
                RelationKind.REFLECTS_FIELD,
                fieldRawRef(normalizedType, normalizedMember),
                "Reflected field was statically described but no extracted symbol matched",
                "static_field_raw_ref",
                true,
                0
        );
    }

    private ResolutionTarget resolveConstructorTarget(
            String rawTargetType,
            List<String> parameterTypes,
            SymbolTable symbols
    ) {
        String normalizedType = normalizeRawType(rawTargetType);
        if (normalizedType == null) {
            return ResolutionTarget.partial(
                    RelationKind.REFLECTS_CONSTRUCTOR,
                    constructorRawRef(null, parameterTypes),
                    "Reflection constructor target type could not be statically determined",
                    "unresolved_constructor",
                    parameterTypes != null && !parameterTypes.isEmpty(),
                    0
            );
        }

        List<SymbolFact> matches = matchingMembers(
                symbols == null ? null : symbols.constructors(),
                normalizedType,
                null,
                parameterTypes
        );
        if (matches.size() == 1) {
            return ResolutionTarget.resolved(
                    RelationKind.REFLECTS_CONSTRUCTOR,
                    matches.get(0).symbol(),
                    parameterTypes.isEmpty()
                            ? "exact_constructor_owner"
                            : "exact_constructor_signature"
            );
        }
        if (matches.size() > 1) {
            return ResolutionTarget.partial(
                    RelationKind.REFLECTS_CONSTRUCTOR,
                    constructorRawRef(normalizedType, parameterTypes),
                    "Multiple reflected constructor candidates matched the extracted symbols",
                    "ambiguous_constructor",
                    true,
                    matches.size()
            );
        }

        return ResolutionTarget.partial(
                RelationKind.REFLECTS_CONSTRUCTOR,
                constructorRawRef(normalizedType, parameterTypes),
                "Reflected constructor was statically described but no extracted symbol matched",
                "static_constructor_raw_ref",
                true,
                0
        );
    }

    private List<SymbolFact> matchingTypes(
            SymbolTable symbols,
            String rawType
    ) {
        if (symbols == null || symbols.types() == null) {
            return List.of();
        }
        return symbols.types().stream()
                .filter(symbol -> symbol != null)
                .filter(symbol -> typeMatches(symbol, rawType))
                .toList();
    }

    private List<SymbolFact> matchingMembers(
            List<SymbolFact> candidates,
            String rawOwnerType,
            String memberName,
            List<String> parameterTypes
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        List<SymbolFact> ownerAndNameMatches = candidates.stream()
                .filter(symbol -> symbol != null)
                .filter(symbol -> ownerMatches(symbol.ownerSymbol(), rawOwnerType))
                .filter(symbol -> memberName == null
                        || memberName.equals(symbol.name()))
                .toList();

        if (parameterTypes == null || parameterTypes.isEmpty()) {
            return ownerAndNameMatches;
        }

        List<SymbolFact> signatureMatches = ownerAndNameMatches.stream()
                .filter(symbol -> symbolContainsParameterTypes(
                        symbol.symbol(),
                        parameterTypes
                ))
                .toList();
        return signatureMatches.isEmpty()
                ? ownerAndNameMatches
                : signatureMatches;
    }

    private boolean typeMatches(SymbolFact symbol, String rawType) {
        String typeSymbol = normalizeTypeSymbol(rawType);
        if (typeSymbol.equals(symbol.symbol())) {
            return true;
        }
        String qualifiedName = trimToNull(symbol.qualifiedName());
        if (rawType.equals(qualifiedName)) {
            return true;
        }
        String simple = simpleName(rawType);
        return simple.equals(symbol.name())
                || (symbol.symbol() != null
                && symbol.symbol().endsWith("." + simple));
    }

    private boolean ownerMatches(String ownerSymbol, String rawOwnerType) {
        if (ownerSymbol == null || rawOwnerType == null) {
            return false;
        }
        if (normalizeTypeSymbol(rawOwnerType).equals(ownerSymbol)) {
            return true;
        }
        return ownerSymbol.endsWith("." + simpleName(rawOwnerType));
    }

    private boolean symbolContainsParameterTypes(
            String symbol,
            List<String> parameterTypes
    ) {
        if (symbol == null) {
            return false;
        }
        for (String parameterType : parameterTypes) {
            String normalized = normalizeRawType(parameterType);
            if (normalized == null) {
                continue;
            }
            if (!symbol.contains(normalized)
                    && !symbol.contains(simpleName(normalized))) {
                return false;
            }
        }
        return true;
    }

    private String targetType(
            ObservationFact observation,
            Map<String, Object> attrs
    ) {
        String targetSymbol = trimToNull(observation.targetSymbol());
        if (targetSymbol != null) {
            return normalizeRawType(targetSymbol);
        }

        TypeRef typeRef = observation.targetTypeRef();
        if (typeRef != null) {
            String raw = firstNonBlank(typeRef.raw(), typeRef.sourceText());
            if (raw != null) {
                return normalizeRawType(raw);
            }
        }

        return normalizeRawType(firstString(
                attrs,
                "target_type",
                "class_name",
                "owner_type"
        ));
    }

    private ReflectionKind reflectionKind(
            String explicitKind,
            String apiMethod,
            String apiOwner
    ) {
        String normalized = trimToNull(explicitKind);
        if (normalized != null) {
            return switch (normalized.toLowerCase()) {
                case "type" -> ReflectionKind.TYPE;
                case "method" -> ReflectionKind.METHOD;
                case "field" -> ReflectionKind.FIELD;
                case "constructor" -> ReflectionKind.CONSTRUCTOR;
                default -> ReflectionKind.UNKNOWN;
            };
        }

        if ("forName".equals(apiMethod)) {
            return ReflectionKind.TYPE;
        }
        if ("getMethod".equals(apiMethod)
                || "getDeclaredMethod".equals(apiMethod)
                || "invoke".equals(apiMethod)) {
            return ReflectionKind.METHOD;
        }
        if ("getField".equals(apiMethod)
                || "getDeclaredField".equals(apiMethod)
                || "java.lang.reflect.Field".equals(apiOwner)) {
            return ReflectionKind.FIELD;
        }
        if ("getConstructor".equals(apiMethod)
                || "getDeclaredConstructor".equals(apiMethod)
                || "newInstance".equals(apiMethod)) {
            return ReflectionKind.CONSTRUCTOR;
        }
        return ReflectionKind.UNKNOWN;
    }

    private String methodRawRef(
            String owner,
            String member,
            List<String> parameterTypes
    ) {
        return "method:"
                + (owner == null ? UNKNOWN_TYPE : owner)
                + "#"
                + (member == null ? UNKNOWN_MEMBER : member)
                + "("
                + String.join(",", parameterTypes == null
                ? List.of()
                : parameterTypes)
                + ")";
    }

    private String fieldRawRef(String owner, String member) {
        return "field:"
                + (owner == null ? UNKNOWN_TYPE : owner)
                + "#"
                + (member == null ? UNKNOWN_MEMBER : member);
    }

    private String constructorRawRef(
            String owner,
            List<String> parameterTypes
    ) {
        return "ctor:"
                + (owner == null ? UNKNOWN_TYPE : owner)
                + "("
                + String.join(",", parameterTypes == null
                ? List.of()
                : parameterTypes)
                + ")";
    }

    private String firstString(Map<String, Object> attrs, String... keys) {
        if (attrs == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object value = attrs.get(key);
            if (value == null) {
                continue;
            }
            String normalized = trimToNull(String.valueOf(value));
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof Iterable<?> iterable)) {
            return List.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (Object item : iterable) {
            String normalized = item == null
                    ? null
                    : trimToNull(String.valueOf(item));
            if (normalized != null) {
                result.add(normalized);
            }
        }
        return List.copyOf(result);
    }

    private Map<String, Object> copyNonNullAttrs(
            Map<String, Object> source
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (source == null) {
            return result;
        }
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    private Map<String, Object> immutableAttrs(
            Map<String, Object> attrs
    ) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(attrs));
    }

    private List<String> sanitizeEvidenceIds(List<String> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : source) {
            String normalized = trimToNull(value);
            if (normalized != null) {
                result.add(normalized);
            }
        }
        return List.copyOf(result);
    }

    private String normalizeTypeSymbol(String value) {
        String raw = normalizeRawType(value);
        return "type:" + (raw == null ? UNKNOWN_TYPE : raw);
    }

    private String normalizeRawType(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return null;
        }
        if (normalized.startsWith("type:")) {
            return normalized.substring("type:".length());
        }
        if (normalized.endsWith(".class")) {
            return normalized.substring(0, normalized.length() - ".class".length());
        }
        return normalized;
    }

    private String simpleName(String value) {
        String normalized = normalizeRawType(value);
        if (normalized == null) {
            return "";
        }
        int index = Math.max(
                normalized.lastIndexOf('.'),
                normalized.lastIndexOf('$')
        );
        return index < 0 ? normalized : normalized.substring(index + 1);
    }

    private String firstNonBlank(String first, String second) {
        String normalized = trimToNull(first);
        return normalized != null ? normalized : trimToNull(second);
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private enum ReflectionKind {
        TYPE("type"),
        METHOD("method"),
        FIELD("field"),
        CONSTRUCTOR("constructor"),
        UNKNOWN("unknown");

        private final String code;

        ReflectionKind(String code) {
            this.code = code;
        }
    }

    private record ResolutionTarget(
            RelationKind relationKind,
            String dstSymbol,
            String dstRawRef,
            boolean symbolResolved,
            boolean referenceKnown,
            boolean referenceAuthoritative,
            boolean inferred,
            int candidateCount,
            String reason,
            String matchStrategy
    ) {
        private ResolutionTarget {
            candidateCount = Math.max(0, candidateCount);
        }

        private static ResolutionTarget resolved(
                RelationKind kind,
                String dstSymbol,
                String matchStrategy
        ) {
            return new ResolutionTarget(
                    kind,
                    dstSymbol,
                    null,
                    true,
                    true,
                    true,
                    false,
                    1,
                    null,
                    matchStrategy
            );
        }

        private static ResolutionTarget partial(
                RelationKind kind,
                String dstRawRef,
                String reason,
                String matchStrategy,
                boolean referenceKnown,
                int candidateCount
        ) {
            return new ResolutionTarget(
                    kind,
                    null,
                    dstRawRef,
                    false,
                    referenceKnown,
                    false,
                    false,
                    candidateCount,
                    reason,
                    matchStrategy
            );
        }
    }
}