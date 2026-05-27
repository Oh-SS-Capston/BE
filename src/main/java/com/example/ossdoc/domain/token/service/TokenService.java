package com.example.ossdoc.domain.token.service;

import com.example.ossdoc.domain.token.dto.response.TokenBalanceResponse;
import com.example.ossdoc.domain.token.dto.response.TokenLedgerResponse;
import com.example.ossdoc.domain.token.entity.TokenLedger;
import com.example.ossdoc.domain.token.entity.UserTokenWallet;
import com.example.ossdoc.domain.token.enums.TokenLedgerType;
import com.example.ossdoc.domain.token.enums.TokenReferenceType;
import com.example.ossdoc.domain.token.exception.TokenException;
import com.example.ossdoc.domain.token.exception.code.TokenErrorCode;
import com.example.ossdoc.domain.token.repository.TokenLedgerRepository;
import com.example.ossdoc.domain.token.repository.UserTokenWalletRepository;
import com.example.ossdoc.domain.user.entity.User;
import com.example.ossdoc.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TokenService {

    private static final int DEFAULT_LEDGER_LIMIT = 30;
    private static final int MAX_LEDGER_LIMIT = 100;

    private final UserRepository userRepository;
    private final UserTokenWalletRepository walletRepository;
    private final TokenLedgerRepository ledgerRepository;

    @Transactional
    public TokenBalanceResponse getMyBalance(Long userId) {
        UserTokenWallet wallet = getOrCreateWallet(userId);
        return TokenBalanceResponse.from(wallet);
    }

    @Transactional(readOnly = true)
    public List<TokenLedgerResponse> getMyLedgers(Long userId, Integer limit) {
        int safeLimit = normalizeLimit(limit);

        return ledgerRepository.findByUser_IdOrderByIdDesc(
                        userId,
                        PageRequest.of(0, safeLimit)
                )
                .stream()
                .map(TokenLedgerResponse::from)
                .toList();
    }

    /*
     * 3차 PortOne 토큰 충전 결제에서 사용할 메서드입니다.
     * 같은 paymentId로 중복 충전되지 않도록 reference 중복 여부를 검사합니다.
     */
    @Transactional
    public TokenBalanceResponse chargeTokens(
            Long userId,
            long amount,
            TokenReferenceType referenceType,
            String referenceId,
            String reason
    ) {
        validatePositiveAmount(amount);

        if (referenceType != null && referenceId != null) {
            boolean duplicated = ledgerRepository.existsByTypeAndReferenceTypeAndReferenceId(
                    TokenLedgerType.TOKEN_CHARGE,
                    referenceType,
                    referenceId
            );

            if (duplicated) {
                return getMyBalance(userId);
            }
        }

        UserTokenWallet wallet = getOrCreateWalletForUpdate(userId);

        wallet.charge(amount);

        ledgerRepository.save(
                TokenLedger.create(
                        wallet.getUser(),
                        amount,
                        wallet.getBalance(),
                        TokenLedgerType.TOKEN_CHARGE,
                        referenceType,
                        referenceId,
                        reason
                )
        );

        return TokenBalanceResponse.from(wallet);
    }

    /*
     * 2차 분석 요청 차감에서 사용할 메서드입니다.
     * amount는 양수로 받고, ledger에는 음수로 기록합니다.
     */
    @Transactional
    public TokenBalanceResponse useTokens(
            Long userId,
            long amount,
            TokenLedgerType ledgerType,
            TokenReferenceType referenceType,
            String referenceId,
            String reason
    ) {
        validatePositiveAmount(amount);
        validateUseLedgerType(ledgerType);

        UserTokenWallet wallet = getOrCreateWalletForUpdate(userId);

        if (wallet.getBalance() < amount) {
            throw new TokenException(
                    TokenErrorCode.INSUFFICIENT_TOKEN,
                    "필요 토큰: " + amount + ", 보유 토큰: " + wallet.getBalance()
            );
        }

        wallet.use(amount);

        ledgerRepository.save(
                TokenLedger.create(
                        wallet.getUser(),
                        -amount,
                        wallet.getBalance(),
                        ledgerType,
                        referenceType,
                        referenceId,
                        reason
                )
        );

        return TokenBalanceResponse.from(wallet);
    }

    @Transactional
    public TokenBalanceResponse refundTokens(
            Long userId,
            long amount,
            TokenReferenceType referenceType,
            String referenceId,
            String reason
    ) {
        validatePositiveAmount(amount);

        UserTokenWallet wallet = getOrCreateWalletForUpdate(userId);

        wallet.refund(amount);

        ledgerRepository.save(
                TokenLedger.create(
                        wallet.getUser(),
                        amount,
                        wallet.getBalance(),
                        TokenLedgerType.PAYMENT_CANCEL_REFUND,
                        referenceType,
                        referenceId,
                        reason
                )
        );

        return TokenBalanceResponse.from(wallet);
    }

    private UserTokenWallet getOrCreateWallet(Long userId) {
        return walletRepository.findByUser_Id(userId)
                .orElseGet(() -> walletRepository.save(UserTokenWallet.create(findUser(userId))));
    }

    private UserTokenWallet getOrCreateWalletForUpdate(Long userId) {
        return walletRepository.findByUserIdForUpdate(userId)
                .orElseGet(() -> walletRepository.saveAndFlush(UserTokenWallet.create(findUser(userId))));
    }

    private User findUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new TokenException(TokenErrorCode.USER_NOT_FOUND));
    }

    private void validatePositiveAmount(long amount) {
        if (amount <= 0) {
            throw new TokenException(TokenErrorCode.INVALID_TOKEN_AMOUNT);
        }
    }

    private void validateUseLedgerType(TokenLedgerType ledgerType) {
        if (ledgerType != TokenLedgerType.ANALYSIS_USE
                && ledgerType != TokenLedgerType.REANALYSIS_USE
                && ledgerType != TokenLedgerType.ADMIN_ADJUSTMENT) {
            throw new TokenException(TokenErrorCode.INVALID_TOKEN_AMOUNT);
        }
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LEDGER_LIMIT;
        }

        if (limit < 1) {
            return DEFAULT_LEDGER_LIMIT;
        }

        return Math.min(limit, MAX_LEDGER_LIMIT);
    }
}