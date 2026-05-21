package com.example.ossdoc.domain.membership.dto.response;

import com.example.ossdoc.domain.membership.enums.MembershipPlan;
import com.example.ossdoc.domain.membership.enums.PaymentProvider;
import com.example.ossdoc.domain.membership.enums.SubscriptionStatus;

import java.time.LocalDateTime;

public record MembershipStatusResponse(
        boolean membershipActive,
        boolean canAnalyze,
        int freeAnalysisLimit,
        int freeAnalysisUsed,
        int freeAnalysisRemaining,
        MembershipPlan plan,
        String planName,
        int amount,
        String currency,
        SubscriptionStatus subscriptionStatus,
        PaymentProvider paymentProvider,
        LocalDateTime currentPeriodEnd,
        String message
) {
}