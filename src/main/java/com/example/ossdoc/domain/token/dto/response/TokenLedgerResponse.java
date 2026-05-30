package com.example.ossdoc.domain.token.dto.response;

import com.example.ossdoc.domain.token.entity.TokenLedger;
import com.example.ossdoc.domain.token.enums.TokenLedgerType;
import com.example.ossdoc.domain.token.enums.TokenReferenceType;
import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record TokenLedgerResponse(
        Long ledgerId,
        Long amount,
        Long balanceAfter,
        TokenLedgerType type,
        TokenReferenceType referenceType,
        String referenceId,
        String reason,
        LocalDateTime createdAt
) {

    public static TokenLedgerResponse from(TokenLedger ledger) {
        return TokenLedgerResponse.builder()
                .ledgerId(ledger.getId())
                .amount(ledger.getAmount())
                .balanceAfter(ledger.getBalanceAfter())
                .type(ledger.getType())
                .referenceType(ledger.getReferenceType())
                .referenceId(ledger.getReferenceId())
                .reason(ledger.getReason())
                .createdAt(ledger.getCreatedAt())
                .build();
    }
}