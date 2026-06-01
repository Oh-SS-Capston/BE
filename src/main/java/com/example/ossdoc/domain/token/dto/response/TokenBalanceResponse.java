package com.example.ossdoc.domain.token.dto.response;

import com.example.ossdoc.domain.token.entity.UserTokenWallet;
import lombok.Builder;

@Builder
public record TokenBalanceResponse(
        Long balance,
        Long totalCharged,
        Long totalUsed
) {

    public static TokenBalanceResponse from(UserTokenWallet wallet) {
        return TokenBalanceResponse.builder()
                .balance(wallet.getBalance())
                .totalCharged(wallet.getTotalCharged())
                .totalUsed(wallet.getTotalUsed())
                .build();
    }
}