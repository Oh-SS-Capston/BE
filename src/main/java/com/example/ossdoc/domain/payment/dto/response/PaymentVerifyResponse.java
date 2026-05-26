package com.example.ossdoc.domain.payment.dto.response;

import com.example.ossdoc.domain.membership.enums.MembershipPlan;
import com.example.ossdoc.domain.payment.enums.PaymentStatus;

import java.time.LocalDateTime;

public record PaymentVerifyResponse(
        String paymentId,
        PaymentStatus paymentStatus,
        MembershipPlan plan,
        String planName,
        int amount,
        String currency,
        boolean membershipActive,
        LocalDateTime currentPeriodEnd,
        String message
) {
}