package com.example.ossdoc.domain.graphstore.model.promotion;

import java.util.List;

/**
 * 10-3-3A~D 결과를 통합 리포트로 조립한다.
 */
public final class SemanticPromotionShadowIntegratedReportFactory {

    private SemanticPromotionShadowIntegratedReportFactory() {
    }

    public static SemanticPromotionShadowIntegratedReport create(
            ObservationPromotionShadowReport contractReport,
            ObservationPromotionCandidateGenerationResult
                    endpointEventSpiGeneration,
            ObservationPromotionCandidateParityReport
                    endpointEventSpiParity,
            ObservationPromotionCandidateGenerationResult
                    beanConfigurationGeneration,
            ObservationPromotionCandidateParityReport
                    beanConfigurationParity,
            ObservationPromotionCandidateGenerationResult
                    reflectionGeneration,
            ObservationPromotionCandidateParityReport
                    reflectionParity,
            ObservationPromotionCandidateGenerationResult
                    diGeneration,
            ObservationPromotionCandidateParityReport
                    diParity
    ) {
        return new SemanticPromotionShadowIntegratedReport(
                contractReport,
                List.of(
                        new SemanticPromotionShadowStageReport(
                                SemanticPromotionShadowStage
                                        .ENDPOINT_EVENT_SPI,
                                endpointEventSpiGeneration,
                                endpointEventSpiParity
                        ),
                        new SemanticPromotionShadowStageReport(
                                SemanticPromotionShadowStage
                                        .BEAN_CONFIGURATION,
                                beanConfigurationGeneration,
                                beanConfigurationParity
                        ),
                        new SemanticPromotionShadowStageReport(
                                SemanticPromotionShadowStage
                                        .REFLECTION,
                                reflectionGeneration,
                                reflectionParity
                        ),
                        new SemanticPromotionShadowStageReport(
                                SemanticPromotionShadowStage
                                        .DI,
                                diGeneration,
                                diParity
                        )
                )
        );
    }
}
