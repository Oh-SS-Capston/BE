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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** ServiceLoader 및 Java module SPI observation을 의미 관계로 승격한다. */
@Component
public class SpiObservationResolver implements ObservationRelationResolver {

    private static final String UNRESOLVED_SERVICE = "<unresolved-service>";

    @Override
    public Set<ObservationKind> supportedKinds() {
        return Set.of(
                ObservationKind.SPI_PROVIDER,
                ObservationKind.MODULE_USES,
                ObservationKind.MODULE_PROVIDES
        );
    }

    @Override
    public int order() {
        return 600;
    }

    @Override
    public ObservationResolutionResult resolve(ObservationResolutionContext context) {
        if (context == null) {
            return new ObservationResolutionResult(
                    List.of(),
                    List.of("SPI resolver received a null context")
            );
        }

        ObservationTable table = context.observations();
        if (table == null) {
            return ObservationResolutionResult.empty();
        }

        List<RelationFact> relations = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        resolveSpiObservations(table.spiProviders(), relations, warnings);
        resolveLoadObservations(
                table.moduleUses(),
                "module_uses",
                relations,
                warnings
        );
        resolveModuleProviders(table.moduleProvides(), relations, warnings);

        return new ObservationResolutionResult(relations, warnings);
    }

    private void resolveSpiObservations(
            List<ObservationFact> observations,
            List<RelationFact> relations,
            List<String> warnings
    ) {
        if (observations == null) {
            return;
        }

        for (ObservationFact observation : observations) {
            if (observation == null) {
                continue;
            }

            String implementation = firstString(
                    observation.attrs(),
                    "implementation",
                    "implementation_type",
                    "provider_type"
            );

            if (implementation != null) {
                resolveProviderObservation(
                        observation,
                        implementation,
                        "spi_provider_observation",
                        relations,
                        warnings
                );
            } else {
                resolveLoadObservation(
                        observation,
                        "service_loader",
                        relations,
                        warnings
                );
            }
        }
    }

    private void resolveLoadObservations(
            List<ObservationFact> observations,
            String mechanism,
            List<RelationFact> relations,
            List<String> warnings
    ) {
        if (observations == null) {
            return;
        }
        for (ObservationFact observation : observations) {
            resolveLoadObservation(
                    observation,
                    mechanism,
                    relations,
                    warnings
            );
        }
    }

    private void resolveLoadObservation(
            ObservationFact observation,
            String mechanism,
            List<RelationFact> relations,
            List<String> warnings
    ) {
        if (observation == null) {
            return;
        }

        String siteSymbol = trimToNull(observation.siteSymbol());
        if (siteSymbol == null) {
            warnings.add(observation.kind() + " observation has no siteSymbol and was skipped");
            return;
        }

        ServiceTarget service = serviceTarget(observation);
        Map<String, Object> attrs = baseAttrs(observation);
        attrs.put("semantic_kind", "spi_service_load");
        attrs.put("mechanism", mechanism);
        attrs.put("service_type", service.rawType());

        RelationFact.RelationFactBuilder builder = baseBuilder(
                observation,
                RelationKind.LOADS_SERVICE,
                siteSymbol,
                service.resolved(),
                "SPI service type could not be fully resolved",
                attrs
        );
        applyDestination(builder, service, "service:");
        relations.add(builder.build());
    }

    private void resolveModuleProviders(
            List<ObservationFact> observations,
            List<RelationFact> relations,
            List<String> warnings
    ) {
        if (observations == null) {
            return;
        }
        for (ObservationFact observation : observations) {
            String implementation = firstString(
                    observation == null ? null : observation.attrs(),
                    "implementation",
                    "implementation_type",
                    "provider_type"
            );
            resolveProviderObservation(
                    observation,
                    implementation,
                    "module_provides",
                    relations,
                    warnings
            );
        }
    }

    private void resolveProviderObservation(
            ObservationFact observation,
            String implementation,
            String mechanism,
            List<RelationFact> relations,
            List<String> warnings
    ) {
        if (observation == null) {
            return;
        }

        String moduleSymbol = trimToNull(observation.siteSymbol());
        String implementationType = trimToNull(implementation);
        ServiceTarget service = serviceTarget(observation);

        if (implementationType == null && moduleSymbol == null) {
            warnings.add(observation.kind() + " observation has no provider or siteSymbol and was skipped");
            return;
        }

        boolean resolved = implementationType != null && service.resolved();
        String sourceSymbol = implementationType == null
                ? moduleSymbol
                : normalizeTypeSymbol(implementationType);

        Map<String, Object> attrs = baseAttrs(observation);
        attrs.put("semantic_kind", "spi_provider");
        attrs.put("mechanism", mechanism);
        attrs.put("service_type", service.rawType());
        if (implementationType != null) {
            attrs.put("implementation_type", rawType(implementationType));
        }
        if (moduleSymbol != null) {
            attrs.put("module_symbol", moduleSymbol);
        }

        RelationFact.RelationFactBuilder builder = baseBuilder(
                observation,
                RelationKind.PROVIDES_SPI,
                sourceSymbol,
                resolved,
                implementationType == null
                        ? "SPI implementation type is missing"
                        : "SPI service type could not be fully resolved",
                attrs
        );
        applyDestination(builder, service, "service:");
        relations.add(builder.build());
    }

    private RelationFact.RelationFactBuilder baseBuilder(
            ObservationFact observation,
            RelationKind kind,
            String sourceSymbol,
            boolean resolved,
            String partialReason,
            Map<String, Object> attrs
    ) {
        double fallback = resolved ? 0.9 : 0.5;
        double confidence = observation.confidenceHint() == null
                ? fallback
                : resolved
                ? observation.confidenceHint()
                : Math.min(observation.confidenceHint(), fallback);

        return RelationFact.builder()
                .kind(kind)
                .srcSymbol(sourceSymbol)
                .evidenceIds(sanitizeEvidenceIds(observation.evidenceIds()))
                .resolution(resolved
                        ? RelationResolutionFactory.resolved()
                        : RelationResolutionFactory.partial(partialReason))
                .origin(observation.origin() == null
                        ? FactOriginKind.OBSERVED
                        : observation.origin())
                .derivation(DerivationKind.DERIVED)
                .confidenceHint(confidence)
                .attrs(Map.copyOf(attrs));
    }

    private void applyDestination(
            RelationFact.RelationFactBuilder builder,
            ServiceTarget service,
            String unresolvedPrefix
    ) {
        if (service.resolved()) {
            builder.dstSymbol(service.typeSymbol());
        } else {
            builder.dstRawRef(unresolvedPrefix + service.rawType());
        }
    }

    private ServiceTarget serviceTarget(ObservationFact observation) {
        String targetSymbol = trimToNull(observation.targetSymbol());
        if (targetSymbol != null) {
            String typeSymbol = normalizeTypeSymbol(targetSymbol);
            return new ServiceTarget(typeSymbol, rawType(typeSymbol), true);
        }

        TypeRef typeRef = observation.targetTypeRef();
        if (typeRef == null) {
            return new ServiceTarget(null, UNRESOLVED_SERVICE, false);
        }

        String raw = firstNonBlank(typeRef.raw(), typeRef.sourceText());
        if (raw == null) {
            return new ServiceTarget(null, UNRESOLVED_SERVICE, false);
        }

        boolean resolved = !Boolean.TRUE.equals(typeRef.unresolved());
        return new ServiceTarget(
                resolved ? normalizeTypeSymbol(raw) : null,
                rawType(raw),
                resolved
        );
    }

    private Map<String, Object> baseAttrs(ObservationFact observation) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        if (observation.attrs() != null) {
            attrs.putAll(observation.attrs());
        }
        attrs.put("resolver", getClass().getSimpleName());
        attrs.put("source_observation_kind", observation.kind().name());
        return attrs;
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

    private String normalizeTypeSymbol(String value) {
        String trimmed = value.trim();
        return trimmed.startsWith("type:") ? trimmed : "type:" + trimmed;
    }

    private String rawType(String value) {
        String trimmed = value == null ? UNRESOLVED_SERVICE : value.trim();
        if (trimmed.startsWith("type:")) {
            return trimmed.substring("type:".length());
        }
        if (trimmed.startsWith("service:")) {
            return trimmed.substring("service:".length());
        }
        return trimmed.isBlank() ? UNRESOLVED_SERVICE : trimmed;
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

    private record ServiceTarget(
            String typeSymbol,
            String rawType,
            boolean resolved
    ) {
    }
}
