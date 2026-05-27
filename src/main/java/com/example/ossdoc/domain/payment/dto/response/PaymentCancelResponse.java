package com.example.ossdoc.domain.payment.dto.response;

import com.example.ossdoc.domain.payment.enums.PaymentStatus;

import java.time.LocalDateTime;

public record PaymentCancelResponse(
        String paymentId,
        PaymentStatus paymentStatus,
        LocalDateTime canceledAt,
        String message
) {
}