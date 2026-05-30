package com.example.ossdoc.domain.token.entity;

import com.example.ossdoc.domain.token.enums.TokenLedgerType;
import com.example.ossdoc.domain.token.enums.TokenReferenceType;
import com.example.ossdoc.domain.user.entity.User;
import com.example.ossdoc.global.apiPayload.code.BaseCreatedEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
        name = "token_ledger",
        indexes = {
                @Index(name = "idx_token_ledger_user_created", columnList = "user_id, created_at"),
                @Index(name = "idx_token_ledger_reference", columnList = "reference_type, reference_id")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class TokenLedger extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ledger_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "user_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_token_ledger_user")
    )
    private User user;

    /*
     * 충전은 양수, 차감은 음수로 저장합니다.
     * 예: +10000, -2000, -500
     */
    @Column(nullable = false)
    private Long amount;

    @Column(name = "balance_after", nullable = false)
    private Long balanceAfter;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private TokenLedgerType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "reference_type", length = 40)
    private TokenReferenceType referenceType;

    @Column(name = "reference_id", length = 100)
    private String referenceId;

    @Column(length = 255)
    private String reason;

    public static TokenLedger create(
            User user,
            long amount,
            long balanceAfter,
            TokenLedgerType type,
            TokenReferenceType referenceType,
            String referenceId,
            String reason
    ) {
        return TokenLedger.builder()
                .user(user)
                .amount(amount)
                .balanceAfter(balanceAfter)
                .type(type)
                .referenceType(referenceType)
                .referenceId(referenceId)
                .reason(reason)
                .build();
    }
}