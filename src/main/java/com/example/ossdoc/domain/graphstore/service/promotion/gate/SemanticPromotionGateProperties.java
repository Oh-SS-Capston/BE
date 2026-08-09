package com.example.ossdoc.domain.graphstore.service.promotion.gate;

import com.example.ossdoc.domain.graphstore.model.promotion.SemanticPromotionShadowStage;
import com.example.ossdoc.domain.graphstore.model.promotion.gate.SemanticPromotionGateScope;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/**
 * 실제 OSS ingest 결과를 이용한 의미 Relation 책임 전환 Gate 설정.
 *
 * <p>candidatePersistenceEnabled의 기본값은 false다.
 * Gate가 READY가 되어도 후속 writer가 연결되기 전에는
 * Shadow 후보를 DB에 저장하지 않는다.</p>
 */
@Component
@ConfigurationProperties(
        prefix = "graphstore.semantic-promotion.gate"
)
public class SemanticPromotionGateProperties {

    private boolean enabled = true;

    private boolean candidatePersistenceEnabled = false;

    private SemanticPromotionGateScope scope =
            SemanticPromotionGateScope.GLOBAL;

    private int requiredConsecutiveSuccesses = 3;

    private int minimumMatchedCandidatesPerRun = 1;

    private long minimumCumulativeMatchedCandidates = 12;

    private int requiredDistinctRepositories = 2;

    private int maxTrackedRunIds = 1_000;

    private Set<SemanticPromotionShadowStage> requiredStages =
            EnumSet.allOf(
                    SemanticPromotionShadowStage.class
            );

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isCandidatePersistenceEnabled() {
        return candidatePersistenceEnabled;
    }

    public void setCandidatePersistenceEnabled(
            boolean candidatePersistenceEnabled
    ) {
        this.candidatePersistenceEnabled =
                candidatePersistenceEnabled;
    }

    public SemanticPromotionGateScope getScope() {
        return scope;
    }

    public void setScope(
            SemanticPromotionGateScope scope
    ) {
        this.scope = scope == null
                ? SemanticPromotionGateScope.GLOBAL
                : scope;
    }

    public int getRequiredConsecutiveSuccesses() {
        return requiredConsecutiveSuccesses;
    }

    public void setRequiredConsecutiveSuccesses(
            int requiredConsecutiveSuccesses
    ) {
        this.requiredConsecutiveSuccesses =
                Math.max(
                        1,
                        requiredConsecutiveSuccesses
                );
    }

    public int getMinimumMatchedCandidatesPerRun() {
        return minimumMatchedCandidatesPerRun;
    }

    public void setMinimumMatchedCandidatesPerRun(
            int minimumMatchedCandidatesPerRun
    ) {
        this.minimumMatchedCandidatesPerRun =
                Math.max(
                        1,
                        minimumMatchedCandidatesPerRun
                );
    }

    public long getMinimumCumulativeMatchedCandidates() {
        return minimumCumulativeMatchedCandidates;
    }

    public void setMinimumCumulativeMatchedCandidates(
            long minimumCumulativeMatchedCandidates
    ) {
        this.minimumCumulativeMatchedCandidates =
                Math.max(
                        1L,
                        minimumCumulativeMatchedCandidates
                );
    }

    public int getRequiredDistinctRepositories() {
        return requiredDistinctRepositories;
    }

    public void setRequiredDistinctRepositories(
            int requiredDistinctRepositories
    ) {
        this.requiredDistinctRepositories =
                Math.max(
                        1,
                        requiredDistinctRepositories
                );
    }

    public int getMaxTrackedRunIds() {
        return maxTrackedRunIds;
    }

    public void setMaxTrackedRunIds(
            int maxTrackedRunIds
    ) {
        this.maxTrackedRunIds =
                Math.max(
                        10,
                        maxTrackedRunIds
                );
    }

    public Set<SemanticPromotionShadowStage>
    getRequiredStages() {
        return Set.copyOf(requiredStages);
    }

    public void setRequiredStages(
            Set<SemanticPromotionShadowStage> requiredStages
    ) {
        if (requiredStages == null
                || requiredStages.isEmpty()) {
            this.requiredStages =
                    EnumSet.allOf(
                            SemanticPromotionShadowStage.class
                    );
            return;
        }

        this.requiredStages =
                EnumSet.copyOf(requiredStages);
    }
}
