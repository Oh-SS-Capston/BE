package com.example.ossdoc.domain.graphstore.model.promotion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ObservationPromotionContractImmutabilityTest {

    @Test
    @DisplayName("승격 계약의 relationKinds와 attrs 계약은 외부에서 변경할 수 없다")
    void contractCollectionsAreImmutable() {
        LinkedHashSet<String> relationKinds =
                new LinkedHashSet<>(
                        Set.of("injects")
                );

        LinkedHashSet<String> semanticKinds =
                new LinkedHashSet<>(
                        Set.of("dependency_injection")
                );

        LinkedHashSet<String> attrs =
                new LinkedHashSet<>(
                        Set.of(
                                "semantic_kind",
                                "resolver"
                        )
                );

        ObservationPromotionContract contract =
                new ObservationPromotionContract(
                        "di_injection_site",
                        relationKinds,
                        semanticKinds,
                        "DiObservationResolver",
                        "derived",
                        ObservationEvidencePolicy
                                .SOURCE_AND_MATCHED_OBSERVATIONS,
                        false,
                        attrs
                );

        relationKinds.add("other_relation");
        semanticKinds.add("other_semantic");
        attrs.add("other_attr");

        assertThrows(
                UnsupportedOperationException.class,
                () ->
                        contract.relationKinds()
                                .add("other")
        );

        assertThrows(
                UnsupportedOperationException.class,
                () ->
                        contract.requiredRelationAttrs()
                                .add("other")
        );
    }
}
