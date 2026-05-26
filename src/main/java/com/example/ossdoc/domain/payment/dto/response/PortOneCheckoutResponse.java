package com.example.ossdoc.domain.payment.dto.response;

import com.example.ossdoc.domain.membership.enums.MembershipPlan;
import com.example.ossdoc.domain.membership.enums.PaymentProvider;

public record PortOneCheckoutResponse(
        String paymentId,
        PaymentProvider paymentProvider,
        String storeId,
        String channelKey,
        MembershipPlan plan,
        String planName,
        int amount,
        String currency,
        String orderName,
        String customerKey,
        String customerEmail,
        String customerName
) {
}