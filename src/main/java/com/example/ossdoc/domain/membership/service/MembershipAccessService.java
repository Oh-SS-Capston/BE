package com.example.ossdoc.domain.membership.service;

import com.example.ossdoc.domain.membership.entity.UserSubscription;
import com.example.ossdoc.domain.membership.entity.UserUsageQuota;
import com.example.ossdoc.domain.membership.enums.AnalysisAccessType;
import com.example.ossdoc.domain.membership.enums.SubscriptionStatus;
import com.example.ossdoc.domain.membership.exception.MembershipException;
import com.example.ossdoc.domain.membership.exception.code.MembershipErrorCode;
import com.example.ossdoc.domain.membership.repository.UserSubscriptionRepository;
import com.example.ossdoc.domain.membership.repository.UserUsageQuotaRepository;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MembershipAccessService {

    private final UserUsageQuotaRepository userUsageQuotaRepository;
    private final UserSubscriptionRepository userSubscriptionRepository;

    @Transactional
    public UserUsageQuota getOrCreateQuota(User user) {
        return userUsageQuotaRepository.findByUser(user)
                .orElseGet(() -> userUsageQuotaRepository.save(
                        UserUsageQuota.builder()
                                .user(user)
                                .freeAnalysisLimit(1)
                                .freeAnalysisUsed(0)
                                .build()
                ));
    }

    public boolean hasActiveMembership(User user) {
        LocalDateTime now = LocalDateTime.now();

        return userSubscriptionRepository
                .findFirstByUserAndStatusInOrderByCreatedAtDesc(
                        user,
                        List.of(SubscriptionStatus.ACTIVE)
                )
                .filter(subscription -> subscription.isActiveAt(now))
                .isPresent();
    }

    public UserSubscription findLatestSubscription(User user) {
        return userSubscriptionRepository
                .findFirstByUserOrderByCreatedAtDesc(user)
                .orElse(null);
    }

    /**
     * 분석 시작 권한을 부여합니다.
     *
     * 정책:
     * 1. ACTIVE 멤버십이 있으면 MEMBERSHIP 분석으로 생성
     * 2. 멤버십이 없고 무료 분석권이 남아 있으면 FREE_TRIAL 분석으로 생성
     * 3. 둘 다 없으면 MEMBERSHIP_REQUIRED 예외
     */
    @Transactional
    public AnalysisAccessType grantAnalysisStart(User user) {
        if (hasActiveMembership(user)) {
            return AnalysisAccessType.MEMBERSHIP;
        }

        UserUsageQuota quota = getOrCreateQuota(user);

        if (quota.hasFreeAnalysis()) {
            quota.consumeFreeAnalysis();
            return AnalysisAccessType.FREE_TRIAL;
        }

        throw new MembershipException(MembershipErrorCode.MEMBERSHIP_REQUIRED);
    }

    /**
     * 분석 결과 조회 권한을 판단합니다.
     *
     * FREE_TRIAL로 생성된 run:
     * - 미결제여도 계속 조회 가능
     *
     * MEMBERSHIP으로 생성된 run:
     * - 현재 ACTIVE 멤버십이 있어야 조회 가능
     */
    public boolean canViewRun(RepoRun run, User user) {
        AnalysisAccessType accessType = run.getAnalysisAccessType();

        if (accessType == AnalysisAccessType.FREE_TRIAL) {
            return true;
        }

        return hasActiveMembership(user);
    }
}