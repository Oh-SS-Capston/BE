package com.example.ossdoc.domain.membership.enums;

import lombok.Getter;

@Getter
public enum MembershipPlan {

    BASIC_MONTHLY(
            "Basic Monthly",
            9_900,
            "KRW",
            1
    );

    private final String displayName;
    private final int amount;
    private final String currency;
    private final int billingCycleMonths;

    MembershipPlan(
            String displayName,
            int amount,
            String currency,
            int billingCycleMonths
    ) {
        this.displayName = displayName;
        this.amount = amount;
        this.currency = currency;
        this.billingCycleMonths = billingCycleMonths;
    }
}