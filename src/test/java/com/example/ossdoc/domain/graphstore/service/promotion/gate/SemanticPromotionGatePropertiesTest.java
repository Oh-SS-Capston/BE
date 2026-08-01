package com.example.ossdoc.domain.graphstore.service.promotion.gate;

import com.example.ossdoc.domain.graphstore.model.promotion.SemanticPromotionShadowStage;
import com.example.ossdoc.domain.graphstore.model.promotion.gate.SemanticPromotionGateScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SemanticPromotionGatePropertiesTest {

    @Test
    @DisplayName("Gate 숫자 설정은 안전한 최소값으로 보정된다")
    void clampsNumericProperties() {
        SemanticPromotionGateProperties properties =
                new SemanticPromotionGateProperties();

        properties.setRequiredConsecutiveSuccesses(0);
        properties.setMinimumMatchedCandidatesPerRun(0);
        properties.setMinimumCumulativeMatchedCandidates(0);
        properties.setRequiredDistinctRepositories(0);
        properties.setMaxTrackedRunIds(1);

        assertEquals(
                1,
                properties.getRequiredConsecutiveSuccesses()
        );
        assertEquals(
                1,
                properties.getMinimumMatchedCandidatesPerRun()
        );
        assertEquals(
                1,
                properties.getMinimumCumulativeMatchedCandidates()
        );
        assertEquals(
                1,
                properties.getRequiredDistinctRepositories()
        );
        assertEquals(
                10,
                properties.getMaxTrackedRunIds()
        );
    }

    @Test
    @DisplayName("빈 requiredStages와 null scope는 안전한 기본값으로 복원된다")
    void restoresSafeDefaults() {
        SemanticPromotionGateProperties properties =
                new SemanticPromotionGateProperties();

        properties.setRequiredStages(Set.of());
        properties.setScope(null);

        assertEquals(
                Set.of(
                        SemanticPromotionShadowStage
                                .ENDPOINT_EVENT_SPI,
                        SemanticPromotionShadowStage
                                .BEAN_CONFIGURATION,
                        SemanticPromotionShadowStage
                                .REFLECTION,
                        SemanticPromotionShadowStage
                                .DI
                ),
                properties.getRequiredStages()
        );

        assertEquals(
                SemanticPromotionGateScope.GLOBAL,
                properties.getScope()
        );
    }
}
