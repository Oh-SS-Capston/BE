package com.example.ossdoc.domain.membership.entity;

import com.example.ossdoc.domain.membership.enums.MembershipPlan;
import com.example.ossdoc.domain.membership.enums.PaymentProvider;
import com.example.ossdoc.domain.membership.enums.SubscriptionStatus;
import com.example.ossdoc.domain.user.entity.User;
import com.example.ossdoc.global.apiPayload.code.BaseAuditedEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "user_subscription",
        indexes = {
                @Index(
                        name = "idx_user_subscription_user_status",
                        columnList = "user_id,status"
                ),
                @Index(
                        name = "idx_user_subscription_period",
                        columnList = "current_period_end"
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class UserSubscription extends BaseAuditedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "subscription_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private MembershipPlan plan;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SubscriptionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_provider", nullable = false, length = 30)
    private PaymentProvider paymentProvider;

    @Column(name = "provider_subscription_id", length = 120)
    private String providerSubscriptionId;

    @Column(name = "provider_customer_id", length = 120)
    private String providerCustomerId;

    @Column(name = "last_payment_id", length = 120)
    private String lastPaymentId;

    @Column(name = "billing_key_ref", length = 200)
    private String billingKeyRef;

    @Column(name = "price_amount", nullable = false)
    private int priceAmount;

    @Column(nullable = false, length = 10)
    private String currency;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "current_period_start")
    private LocalDateTime currentPeriodStart;

    @Column(name = "current_period_end")
    private LocalDateTime currentPeriodEnd;

    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    public static UserSubscription pending(
            User user,
            MembershipPlan plan,
            PaymentProvider paymentProvider
    ) {
        return UserSubscription.builder()
                .user(user)
                .plan(plan)
                .status(SubscriptionStatus.PENDING)
                .paymentProvider(paymentProvider)
                .priceAmount(plan.getAmount())
                .currency(plan.getCurrency())
                .build();
    }

    public boolean isActiveAt(LocalDateTime now) {
        if (status != SubscriptionStatus.ACTIVE) {
            return false;
        }

        return currentPeriodEnd == null || currentPeriodEnd.isAfter(now);
    }

    public void activateByPayment(
            String paymentId,
            LocalDateTime now,
            LocalDateTime periodEnd
    ) {
        this.status = SubscriptionStatus.ACTIVE;
        this.startedAt = this.startedAt == null ? now : this.startedAt;
        this.currentPeriodStart = now;
        this.currentPeriodEnd = periodEnd;
        this.lastPaymentId = paymentId;
        this.canceledAt = null;
    }

    public void cancelAtPeriodEnd(LocalDateTime now) {
        this.status = SubscriptionStatus.CANCELED;
        this.canceledAt = now;
    }

    public void markPastDue() {
        this.status = SubscriptionStatus.PAST_DUE;
    }

    public void expire() {
        this.status = SubscriptionStatus.EXPIRED;
    }
}