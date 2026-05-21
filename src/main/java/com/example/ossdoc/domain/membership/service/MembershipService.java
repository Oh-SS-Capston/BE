package com.example.ossdoc.domain.membership.service;

import com.example.ossdoc.domain.auth.exception.AuthException;
import com.example.ossdoc.domain.auth.exception.code.AuthErrorCode;
import com.example.ossdoc.domain.membership.dto.response.MembershipStatusResponse;
import com.example.ossdoc.domain.membership.entity.UserSubscription;
import com.example.ossdoc.domain.membership.entity.UserUsageQuota;
import com.example.ossdoc.domain.membership.enums.MembershipPlan;
import com.example.ossdoc.domain.membership.enums.PaymentProvider;
import com.example.ossdoc.domain.user.entity.User;
import com.example.ossdoc.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MembershipService {

    private static final MembershipPlan DEFAULT_PLAN = MembershipPlan.BASIC_MONTHLY;
    private static final PaymentProvider DEFAULT_PAYMENT_PROVIDER = PaymentProvider.PORTONE_V2;

    private final UserRepository userRepository;
    private final MembershipAccessService membershipAccessService;

    @Transactional
    public MembershipStatusResponse getMyMembership(Long userId) {
        User user = getActiveUser(userId);

        UserUsageQuota quota = membershipAccessService.getOrCreateQuota(user);
        UserSubscription latestSubscription = membershipAccessService.findLatestSubscription(user);

        boolean membershipActive = membershipAccessService.hasActiveMembership(user);
        int freeRemaining = quota.remainingFreeAnalyses();
        boolean canAnalyze = membershipActive || freeRemaining > 0;

        String message = resolveMessage(membershipActive, freeRemaining);

        return new MembershipStatusResponse(
                membershipActive,
                canAnalyze,
                quota.getFreeAnalysisLimit(),
                quota.getFreeAnalysisUsed(),
                freeRemaining,
                DEFAULT_PLAN,
                DEFAULT_PLAN.getDisplayName(),
                DEFAULT_PLAN.getAmount(),
                DEFAULT_PLAN.getCurrency(),
                latestSubscription == null ? null : latestSubscription.getStatus(),
                latestSubscription == null
                        ? DEFAULT_PAYMENT_PROVIDER
                        : latestSubscription.getPaymentProvider(),
                latestSubscription == null ? null : latestSubscription.getCurrentPeriodEnd(),
                message
        );
    }

    private User getActiveUser(Long userId) {
        return userRepository.findById(userId)
                .filter(User::isActive)
                .orElseThrow(() -> new AuthException(AuthErrorCode.USER_NOT_FOUND));
    }

    private String resolveMessage(boolean membershipActive, int freeRemaining) {
        if (membershipActive) {
            return "멤버십이 활성화되어 분석을 계속 사용할 수 있습니다.";
        }

        if (freeRemaining > 0) {
            return "무료 분석 기회 1회가 남아 있습니다.";
        }

        return "무료 분석 기회를 모두 사용했습니다. 멤버십 가입 후 분석할 수 있습니다.";
    }
}