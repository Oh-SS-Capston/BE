package com.example.ossdoc.domain.payment.dto.portone;

import java.time.LocalDateTime;

public record PortOnePaymentSnapshot(
        String paymentId,
        String status,
        Integer amount,
        String currency,
        String transactionId,
        LocalDateTime paidAt,
        LocalDateTime canceledAt
) {
}