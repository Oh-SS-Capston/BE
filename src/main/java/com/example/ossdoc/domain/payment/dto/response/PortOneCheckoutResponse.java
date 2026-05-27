package com.example.ossdoc.domain.payment.dto.response;

import com.example.ossdoc.domain.membership.enums.PaymentProvider;

public record PortOneCheckoutResponse(
        String paymentId,
        PaymentProvider paymentProvider,
        String storeId,
        String channelKey,

        Integer chargeAmount,
        Integer tokenAmount,

        String currency,
        String orderName,
        String customerKey,
        String customerEmail,
        String customerName
) {
}