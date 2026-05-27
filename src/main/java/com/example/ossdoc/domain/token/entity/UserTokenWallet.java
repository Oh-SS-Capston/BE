package com.example.ossdoc.domain.token.entity;

import com.example.ossdoc.domain.user.entity.User;
import com.example.ossdoc.global.apiPayload.code.BaseAuditedEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
        name = "user_token_wallet",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_user_token_wallet_user",
                        columnNames = "user_id"
                )
        },
        indexes = {
                @Index(name = "idx_user_token_wallet_user", columnList = "user_id")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class UserTokenWallet extends BaseAuditedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "wallet_id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "user_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_user_token_wallet_user")
    )
    private User user;

    @Column(nullable = false)
    @Builder.Default
    private Long balance = 0L;

    @Column(name = "total_charged", nullable = false)
    @Builder.Default
    private Long totalCharged = 0L;

    @Column(name = "total_used", nullable = false)
    @Builder.Default
    private Long totalUsed = 0L;

    public static UserTokenWallet create(User user) {
        return UserTokenWallet.builder()
                .user(user)
                .balance(0L)
                .totalCharged(0L)
                .totalUsed(0L)
                .build();
    }

    public void charge(long amount) {
        validatePositiveAmount(amount);

        this.balance += amount;
        this.totalCharged += amount;
    }

    public void use(long amount) {
        validatePositiveAmount(amount);

        if (this.balance < amount) {
            throw new IllegalStateException("토큰 잔액이 부족합니다.");
        }

        this.balance -= amount;
        this.totalUsed += amount;
    }

    public void refund(long amount) {
        validatePositiveAmount(amount);

        this.balance += amount;

        if (this.totalUsed >= amount) {
            this.totalUsed -= amount;
        }
    }

    private void validatePositiveAmount(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("토큰 금액은 0보다 커야 합니다.");
        }
    }
}