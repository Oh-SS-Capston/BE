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
import com.example.ossdoc.domain.token.support.TokenPolicy;
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

    /*
     * 로그인한 사용자의 토큰 잔액을 조회합니다.
     *
     * 지갑이 없으면 최초 1회 생성하면서 무료 분석 1회분 토큰을 지급합니다.
     */
    @Transactional
    public TokenBalanceResponse getMyBalance(Long userId) {
        UserTokenWallet wallet = getOrCreateWallet(userId);
        return TokenBalanceResponse.from(wallet);
    }

    /*
     * 로그인한 사용자의 토큰 충전/차감 내역을 조회합니다.
     */
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
     * PortOne 토큰 충전 결제 검증 성공 시 호출됩니다.
     *
     * 정상 흐름:
     * 1. 사용자가 토큰 충전 결제
     * 2. PortOne 결제 성공
     * 3. 백엔드가 PortOne 결제 단건 조회로 검증
     * 4. 검증 성공 시 이 메서드로 토큰 자동 충전
     *
     * 관리자가 직접 지급하는 구조가 아닙니다.
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

        /*
         * 같은 paymentId로 중복 검증되어도 토큰이 두 번 충전되지 않도록 방지합니다.
         */
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
     * 분석 요청 시 토큰을 차감합니다.
     *
     * 일반 분석: 2,000토큰
     * 재분석: 500토큰
     *
     * amount는 양수로 받고, token_ledger에는 음수로 기록합니다.
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

    private UserTokenWallet getOrCreateWallet(Long userId) {
        return walletRepository.findByUser_Id(userId)
                .orElseGet(() -> createWalletWithSignupBonus(findUser(userId)));
    }

    private UserTokenWallet getOrCreateWalletForUpdate(Long userId) {
        return walletRepository.findByUserIdForUpdate(userId)
                .orElseGet(() -> createWalletWithSignupBonus(findUser(userId)));
    }

    /*
     * 신규 사용자 토큰 지갑을 생성하고 무료 분석 1회분 토큰을 지급합니다.
     *
     * 현재 정책:
     * - 일반 분석 1회 비용: 2,000토큰
     * - 신규 사용자 무료 지급: 2,500토큰
     *
     * 탈퇴 후 재가입해도 무료 토큰이 다시 지급되지 않게 하려면,
     * user row와 user_token_wallet을 삭제하지 않고 비활성화 상태로 유지해야 합니다.
     */
    private UserTokenWallet createWalletWithSignupBonus(User user) {
        UserTokenWallet wallet = UserTokenWallet.create(user);

        wallet.charge(TokenPolicy.SIGNUP_BONUS_TOKENS);

        UserTokenWallet savedWallet = walletRepository.saveAndFlush(wallet);

        ledgerRepository.save(
                TokenLedger.create(
                        user,
                        TokenPolicy.SIGNUP_BONUS_TOKENS,
                        savedWallet.getBalance(),
                        TokenLedgerType.SIGNUP_BONUS,
                        TokenReferenceType.SYSTEM,
                        "signup-bonus-user-" + user.getId(),
                        "신규 가입 무료 분석 토큰 지급"
                )
        );

        return savedWallet;
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
                && ledgerType != TokenLedgerType.REANALYSIS_USE) {
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