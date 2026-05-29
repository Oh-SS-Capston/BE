package com.example.ossdoc.domain.payment.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record TokenChargeCheckoutRequest(
        /*
         * 1 KRW = 1 Token입니다.
         * 현재는 최소 1,000원, 최대 1,000,000원까지 허용합니다.
         */
        @NotNull(message = "충전 금액은 필수입니다.")
        @Min(value = 1000, message = "최소 충전 금액은 1,000원입니다.")
        @Max(value = 1000000, message = "최대 충전 금액은 1,000,000원입니다.")
        Integer amount
) {
}