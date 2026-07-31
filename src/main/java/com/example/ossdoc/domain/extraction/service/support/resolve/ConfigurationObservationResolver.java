package com.example.ossdoc.domain.extraction.service.support.resolve;

import com.example.ossdoc.domain.extraction.dto.model.ObservationFact;
import com.example.ossdoc.domain.extraction.dto.model.ObservationTable;
import com.example.ossdoc.domain.extraction.dto.model.RelationFact;
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

/** CONFIG_WIRING observation을 CONFIGURES_BEAN 의미 관계로 승격한다. */
@Component
public class ConfigurationObservationResolver
        implements ObservationRelationResolver {

    private RelationResolutionPolicy resolutionPolicy;
    private RelationConfidencePolicy confidencePolicy;

    public ConfigurationObservationResolver() {
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
        return Set.of(ObservationKind.CONFIG_WIRING);
    }

    @Override
    public int order() {
        return 300;
    }

    @Override
    public ObservationResolutionResult resolve(
            ObservationResolutionContext context
    ) {
        if (context == null) {
            return new ObservationResolutionResult(
                    List.of(),
                    List.of("Configuration resolver received a null context")
            );
        }

        ObservationTable observationTable = context.observations();
        List<ObservationFact> wiringObservations = observationTable == null
                || observationTable.configWiring() == null
                ? List.of()
                : observationTable.configWiring();

        if (wiringObservations.isEmpty()) {
            return ObservationResolutionResult.empty();
        }

        List<RelationFact> relations = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (ObservationFact observation : wiringObservations) {
            resolveWiring(observation, relations, warnings);
        }
        return new ObservationResolutionResult(relations, warnings);
    }

    private void resolveWiring(
            ObservationFact observation,
            List<RelationFact> relations,
            List<String> warnings
    ) {
        if (observation == null) {
            return;
        }

        String siteSymbol = trimToNull(observation.siteSymbol());
        if (siteSymbol == null) {
            warnings.add("CONFIG_WIRING observation has no siteSymbol and was skipped");
            return;
        }

        Map<String, Object> attrs = observation.attrs() == null
                ? Map.of()
                : observation.attrs();
        List<String> importedTypes = normalizeTypeNames(
                stringList(attrs.get("imported_types"))
        );
        List<String> scanPackages = normalizePackages(
                stringList(attrs.get("component_scan_packages"))
        );

        for (String importedType : importedTypes) {
            relations.add(buildRelation(
                    observation,
                    "type:" + importedType,
                    "import_type",
                    importedType
            ));
        }
        for (String scanPackage : scanPackages) {
            relations.add(buildRelation(
                    observation,
                    "package:" + scanPackage,
                    "component_scan_package",
                    scanPackage
            ));
        }
    }

    private RelationFact buildRelation(
            ObservationFact observation,
            String targetReference,
            String wiringKind,
            String wiringTarget
    ) {
        List<String> evidenceIds = sanitizeEvidenceIds(observation.evidenceIds());
        FactOriginKind origin = observation.origin() == null
                ? FactOriginKind.OBSERVED
                : observation.origin();

        RelationPolicyInput policyInput = RelationPolicyInput.builder()
                .origin(origin)
                .derivation(DerivationKind.DERIVED)
                .targetReferenceKnown(true)
                .targetReferenceAuthoritative(true)
                .candidateCount(1)
                .evidencePresent(!evidenceIds.isEmpty())
                .sourceConfidenceHint(observation.confidenceHint())
                .build();
        ResolutionAssessment resolution = resolutionPolicy.assess(policyInput);
        ConfidenceAssessment confidence = confidencePolicy.assess(
                policyInput,
                resolution
        );

        Map<String, Object> attrs = copyNonNullAttrs(observation.attrs());
        attrs.put("wiring_kind", wiringKind);
        attrs.put("wiring_target", wiringTarget);
        attrs.put("semantic_kind", "configuration_wiring");
        attrs.put("resolver", getClass().getSimpleName());
        putPolicyAttrs(attrs, resolution, confidence);

        return RelationFact.builder()
                .kind(RelationKind.CONFIGURES_BEAN)
                .srcSymbol(observation.siteSymbol())
                .dstRawRef(targetReference)
                .evidenceIds(evidenceIds)
                .resolution(resolution.toRelationResolution())
                .origin(origin)
                .derivation(DerivationKind.DERIVED)
                .confidenceHint(confidence.value())
                .attrs(Collections.unmodifiableMap(new LinkedHashMap<>(attrs)))
                .build();
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

    private List<String> normalizeTypeNames(List<String> rawTypes) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String rawType : rawTypes) {
            String typeName = trimToNull(rawType);
            if (typeName == null) {
                continue;
            }
            if (typeName.startsWith("type:")) {
                typeName = trimToNull(typeName.substring("type:".length()));
            }
            if (typeName != null && typeName.endsWith(".class")) {
                typeName = trimToNull(
                        typeName.substring(0, typeName.length() - ".class".length())
                );
            }
            if (typeName != null) {
                normalized.add(typeName);
            }
        }
        return List.copyOf(normalized);
    }

    private List<String> normalizePackages(List<String> rawPackages) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String rawPackage : rawPackages) {
            String packageName = trimToNull(rawPackage);
            if (packageName == null) {
                continue;
            }
            if (packageName.startsWith("package:")) {
                packageName = trimToNull(
                        packageName.substring("package:".length())
                );
            }
            while (packageName != null && packageName.endsWith(".")) {
                packageName = trimToNull(
                        packageName.substring(0, packageName.length() - 1)
                );
            }
            if (packageName != null) {
                normalized.add(packageName);
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

    private void collectStrings(Object raw, Collection<String> destination) {
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
        Class<?> rawType = raw.getClass();
        if (rawType.isArray()) {
            int length = Array.getLength(raw);
            for (int index = 0; index < length; index++) {
                collectStrings(Array.get(raw, index), destination);
            }
        }
    }

    private Map<String, Object> copyNonNullAttrs(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        return copy;
    }

    private List<String> sanitizeEvidenceIds(List<String> evidenceIds) {
        if (evidenceIds == null || evidenceIds.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> sanitized = new LinkedHashSet<>();
        for (String evidenceId : evidenceIds) {
            String value = trimToNull(evidenceId);
            if (value != null) {
                sanitized.add(value);
            }
        }
        return List.copyOf(sanitized);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
