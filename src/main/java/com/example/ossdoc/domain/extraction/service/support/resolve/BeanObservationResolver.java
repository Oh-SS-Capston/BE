package com.example.ossdoc.domain.extraction.service.support.resolve;

import com.example.ossdoc.domain.extraction.dto.model.ObservationFact;
import com.example.ossdoc.domain.extraction.dto.model.ObservationTable;
import com.example.ossdoc.domain.extraction.dto.model.RelationFact;
import com.example.ossdoc.domain.extraction.dto.model.TypeRef;
import com.example.ossdoc.domain.extraction.enums.DerivationKind;
import com.example.ossdoc.domain.extraction.enums.FactOriginKind;
import com.example.ossdoc.domain.extraction.enums.ObservationKind;
import com.example.ossdoc.domain.extraction.enums.RelationKind;
import com.example.ossdoc.domain.extraction.service.support.util.RelationResolutionFactory;
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
 * DI_PROVIDER observation을 DECLARES_BEAN 의미 관계로 승격한다.
 *
 * <p>명시된 Bean 이름이나 alias가 여러 개면 이름마다 relation을 생성한다.
 * Bean 이름이 없지만 제공 타입 또는 provider symbol에서 이름을 추론할 수 있으면
 * PARTIAL relation으로 보존한다.</p>
 */
@Component
public class BeanObservationResolver
        implements ObservationRelationResolver {

    @Override
    public Set<ObservationKind> supportedKinds() {
        return Set.of(ObservationKind.DI_PROVIDER);
    }

    @Override
    public int order() {
        return 200;
    }

    @Override
    public ObservationResolutionResult resolve(
            ObservationResolutionContext context
    ) {
        if (context == null) {
            return new ObservationResolutionResult(
                    List.of(),
                    List.of("Bean resolver received a null context")
            );
        }

        ObservationTable observationTable = context.observations();
        List<ObservationFact> providers = observationTable == null
                || observationTable.diProviders() == null
                ? List.of()
                : observationTable.diProviders();

        if (providers.isEmpty()) {
            return ObservationResolutionResult.empty();
        }

        List<RelationFact> relations = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (ObservationFact provider : providers) {
            resolveProvider(provider, relations, warnings);
        }

        return new ObservationResolutionResult(
                relations,
                warnings
        );
    }

    private void resolveProvider(
            ObservationFact provider,
            List<RelationFact> relations,
            List<String> warnings
    ) {
        if (provider == null) {
            return;
        }

        String siteSymbol = trimToNull(provider.siteSymbol());
        if (siteSymbol == null) {
            warnings.add(
                    "DI_PROVIDER observation has no siteSymbol and was skipped"
            );
            return;
        }

        Map<String, Object> observationAttrs = provider.attrs() == null
                ? Map.of()
                : provider.attrs();

        String providedType = resolveProvidedType(
                provider.targetTypeRef(),
                observationAttrs.get("provided_type")
        );

        List<String> declaredBeanNames = normalizeNames(
                stringList(observationAttrs.get("bean_names"))
        );

        NameResolution names = declaredBeanNames.isEmpty()
                ? inferBeanNames(siteSymbol, providedType)
                : new NameResolution(
                        declaredBeanNames,
                        true,
                        null
                );

        if (names.beanNames().isEmpty()) {
            warnings.add(
                    "DI_PROVIDER observation could not determine a Bean name: "
                            + siteSymbol
            );
            return;
        }

        for (String beanName : names.beanNames()) {
            relations.add(buildRelation(
                    provider,
                    beanName,
                    providedType,
                    names.declared(),
                    names.partialReason()
            ));
        }
    }

    private RelationFact buildRelation(
            ObservationFact provider,
            String beanName,
            String providedType,
            boolean declaredName,
            String partialReason
    ) {
        Map<String, Object> attrs = copyNonNullAttrs(provider.attrs());
        attrs.put("bean_name", beanName);
        attrs.put("bean_reference", "bean:" + beanName);
        attrs.put("name_resolution", declaredName ? "declared" : "inferred");
        attrs.put("semantic_kind", "bean_declaration");
        attrs.put("resolver", getClass().getSimpleName());

        if (providedType != null) {
            attrs.put("provided_type", providedType);
        }

        double fallbackConfidence = declaredName ? 0.9 : 0.7;
        double confidence = provider.confidenceHint() == null
                ? fallbackConfidence
                : declaredName
                ? provider.confidenceHint()
                : Math.min(provider.confidenceHint(), fallbackConfidence);

        return RelationFact.builder()
                .kind(RelationKind.DECLARES_BEAN)
                .srcSymbol(provider.siteSymbol())
                .dstRawRef("bean:" + beanName)
                .evidenceIds(sanitizeEvidenceIds(provider.evidenceIds()))
                .resolution(
                        declaredName
                                ? RelationResolutionFactory.resolved()
                                : RelationResolutionFactory.partial(partialReason)
                )
                .origin(provider.origin() == null
                        ? FactOriginKind.OBSERVED
                        : provider.origin())
                .derivation(DerivationKind.DERIVED)
                .confidenceHint(confidence)
                .attrs(Collections.unmodifiableMap(
                        new LinkedHashMap<>(attrs)
                ))
                .build();
    }

    private NameResolution inferBeanNames(
            String siteSymbol,
            String providedType
    ) {
        String fromType = defaultBeanNameFromType(providedType);
        if (fromType != null) {
            return new NameResolution(
                    List.of(fromType),
                    false,
                    "Bean name inferred from provided type"
            );
        }

        String fromSymbol = providerNameFromSymbol(siteSymbol);
        if (fromSymbol != null) {
            return new NameResolution(
                    List.of(fromSymbol),
                    false,
                    "Bean name inferred from provider symbol"
            );
        }

        return new NameResolution(
                List.of(),
                false,
                "Bean name could not be resolved"
        );
    }

    private String resolveProvidedType(
            TypeRef targetTypeRef,
            Object rawProvidedType
    ) {
        String fromAttrs = trimToNull(
                rawProvidedType == null
                        ? null
                        : String.valueOf(rawProvidedType)
        );

        if (fromAttrs != null) {
            return fromAttrs;
        }

        return targetTypeRef == null
                ? null
                : trimToNull(targetTypeRef.raw());
    }

    private String defaultBeanNameFromType(String providedType) {
        String normalized = trimToNull(providedType);
        if (normalized == null
                || "void".equals(normalized)) {
            return null;
        }

        int packageSeparator = normalized.lastIndexOf('.');
        int nestedSeparator = normalized.lastIndexOf('$');
        int separator = Math.max(packageSeparator, nestedSeparator);

        String simpleName = separator >= 0
                ? normalized.substring(separator + 1)
                : normalized;

        return trimToNull(decapitalize(simpleName));
    }


    private String decapitalize(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return null;
        }

        if (normalized.length() > 1
                && Character.isUpperCase(normalized.charAt(0))
                && Character.isUpperCase(normalized.charAt(1))) {
            return normalized;
        }

        return Character.toLowerCase(normalized.charAt(0))
                + normalized.substring(1);
    }

    private String providerNameFromSymbol(String siteSymbol) {
        String symbol = trimToNull(siteSymbol);
        if (symbol == null) {
            return null;
        }

        int hashIndex = symbol.lastIndexOf('#');
        if (hashIndex >= 0 && hashIndex + 1 < symbol.length()) {
            String methodPart = symbol.substring(hashIndex + 1);
            int parameterIndex = methodPart.indexOf('(');
            if (parameterIndex >= 0) {
                methodPart = methodPart.substring(0, parameterIndex);
            }
            return trimToNull(methodPart);
        }

        if (symbol.startsWith("type:")) {
            return defaultBeanNameFromType(
                    symbol.substring("type:".length())
            );
        }

        return null;
    }

    private List<String> normalizeNames(List<String> rawNames) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();

        for (String rawName : rawNames) {
            String name = trimToNull(rawName);
            if (name != null) {
                normalized.add(name);
            }
        }

        return List.copyOf(normalized);
    }

    private List<String> stringList(Object raw) {
        if (raw == null) {
            return List.of();
        }

        LinkedHashSet<String> result = new LinkedHashSet<>();
        collectStrings(raw, result);
        return List.copyOf(result);
    }

    private void collectStrings(
            Object raw,
            Collection<String> destination
    ) {
        if (raw == null) {
            return;
        }

        if (raw instanceof CharSequence sequence) {
            String value = trimToNull(sequence.toString());
            if (value != null) {
                destination.add(value);
            }
            return;
        }

        if (raw instanceof Collection<?> collection) {
            for (Object item : collection) {
                collectStrings(item, destination);
            }
            return;
        }

        if (raw.getClass().isArray()) {
            int length = Array.getLength(raw);
            for (int index = 0; index < length; index++) {
                collectStrings(Array.get(raw, index), destination);
            }
            return;
        }

        String value = trimToNull(String.valueOf(raw));
        if (value != null) {
            destination.add(value);
        }
    }

    private Map<String, Object> copyNonNullAttrs(
            Map<String, Object> source
    ) {
        Map<String, Object> copied = new LinkedHashMap<>();

        if (source == null || source.isEmpty()) {
            return copied;
        }

        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            copied.put(entry.getKey(), entry.getValue());
        }

        return copied;
    }

    private List<String> sanitizeEvidenceIds(List<String> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String evidenceId : source) {
            String normalized = trimToNull(evidenceId);
            if (normalized != null) {
                result.add(normalized);
            }
        }
        return List.copyOf(result);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record NameResolution(
            List<String> beanNames,
            boolean declared,
            String partialReason
    ) {
    }
}
