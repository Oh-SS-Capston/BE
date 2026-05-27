package com.example.ossdoc.domain.membership.entity;

import com.example.ossdoc.domain.user.entity.User;
import com.example.ossdoc.global.apiPayload.code.BaseAuditedEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
        name = "user_usage_quota",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_user_usage_quota_user",
                        columnNames = "user_id"
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class UserUsageQuota extends BaseAuditedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "usage_quota_id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "free_analysis_limit", nullable = false)
    @Builder.Default
    private int freeAnalysisLimit = 1;

    @Column(name = "free_analysis_used", nullable = false)
    @Builder.Default
    private int freeAnalysisUsed = 0;

    public int remainingFreeAnalyses() {
        return Math.max(0, freeAnalysisLimit - freeAnalysisUsed);
    }

    public boolean hasFreeAnalysis() {
        return remainingFreeAnalyses() > 0;
    }

    public void consumeFreeAnalysis() {
        if (!hasFreeAnalysis()) {
            throw new IllegalStateException("무료 분석 기회가 남아 있지 않습니다.");
        }

        this.freeAnalysisUsed += 1;
    }
}