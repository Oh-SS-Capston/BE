package com.example.ossdoc.domain.payment.dto.request;

import jakarta.validation.constraints.NotBlank;

public record PaymentVerifyRequest(
        @NotBlank(message = "paymentId는 필수입니다.")
        String paymentId
) {
}