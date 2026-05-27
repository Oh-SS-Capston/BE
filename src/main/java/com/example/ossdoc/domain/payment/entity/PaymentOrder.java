package com.example.ossdoc.domain.payment.entity;

import com.example.ossdoc.domain.membership.enums.PaymentProvider;
import com.example.ossdoc.domain.payment.enums.PaymentStatus;
import com.example.ossdoc.domain.user.entity.User;
import com.example.ossdoc.global.apiPayload.code.BaseAuditedEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "payment_order",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_payment_order_payment_id",
                        columnNames = "payment_id"
                )
        },
        indexes = {
                @Index(
                        name = "idx_payment_order_user_status",
                        columnList = "user_id,status"
                ),
                @Index(
                        name = "idx_payment_order_created_at",
                        columnList = "created_at"
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class PaymentOrder extends BaseAuditedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payment_order_id")
    private Long id;

    @Column(name = "payment_id", nullable = false, length = 120)
    private String paymentId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "user_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_payment_order_user")
    )
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentProvider provider;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentStatus status;

    @Column(name = "order_name", nullable = false, length = 120)
    private String orderName;

    /*
     * 실제 결제 금액입니다.
     * 1 KRW = 1 Token이므로 amount와 tokenAmount는 현재 동일합니다.
     */
    @Column(nullable = false)
    private int amount;

    @Column(name = "token_amount", nullable = false)
    private int tokenAmount;

    @Column(nullable = false, length = 10)
    private String currency;

    @Column(name = "customer_key", nullable = false, length = 120)
    private String customerKey;

    @Column(name = "provider_status", length = 60)
    private String providerStatus;

    @Column(name = "provider_transaction_id", length = 120)
    private String providerTransactionId;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "failed_at")
    private LocalDateTime failedAt;

    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

    public static PaymentOrder readyForTokenCharge(
            User user,
            String paymentId,
            String orderName,
            int amount,
            int tokenAmount,
            String currency,
            String customerKey
    ) {
        return PaymentOrder.builder()
                .paymentId(paymentId)
                .user(user)
                .provider(PaymentProvider.PORTONE_V2)
                .status(PaymentStatus.READY)
                .orderName(orderName)
                .amount(amount)
                .tokenAmount(tokenAmount)
                .currency(currency)
                .customerKey(customerKey)
                .build();
    }

    public boolean isPaid() {
        return status == PaymentStatus.PAID;
    }

    public boolean isCanceled() {
        return status == PaymentStatus.CANCELED;
    }

    public void markPaid(
            String providerStatus,
            String providerTransactionId,
            LocalDateTime paidAt
    ) {
        this.status = PaymentStatus.PAID;
        this.providerStatus = providerStatus;
        this.providerTransactionId = providerTransactionId;
        this.paidAt = paidAt == null ? LocalDateTime.now() : paidAt;
        this.failedAt = null;
        this.failureReason = null;
        this.canceledAt = null;
        this.cancelReason = null;
    }

    public void markFailed(
            String providerStatus,
            String failureReason
    ) {
        this.status = PaymentStatus.FAILED;
        this.providerStatus = providerStatus;
        this.failedAt = LocalDateTime.now();
        this.failureReason = failureReason;
    }

    public void markCanceled(
            String providerStatus,
            String cancelReason,
            LocalDateTime canceledAt
    ) {
        this.status = PaymentStatus.CANCELED;
        this.providerStatus = providerStatus;
        this.canceledAt = canceledAt == null ? LocalDateTime.now() : canceledAt;
        this.cancelReason = cancelReason;
    }
}