package com.example.ossdoc.domain.payment.dto.response;

import com.example.ossdoc.domain.payment.enums.PaymentStatus;

public record PaymentVerifyResponse(
        String paymentId,
        PaymentStatus paymentStatus,

        Integer chargeAmount,
        Integer chargedTokens,
        Long tokenBalance,

        String currency,
        String message
) {
}