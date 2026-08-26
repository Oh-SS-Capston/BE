package com.example.ossdoc.domain.payment.service;

import com.example.ossdoc.domain.auth.exception.AuthException;
import com.example.ossdoc.domain.auth.exception.code.AuthErrorCode;
import com.example.ossdoc.domain.payment.enums.PaymentProvider;
import com.example.ossdoc.domain.payment.client.PortOnePaymentClient;
import com.example.ossdoc.domain.payment.dto.portone.PortOnePaymentSnapshot;
import com.example.ossdoc.domain.payment.dto.request.PaymentCancelRequest;
import com.example.ossdoc.domain.payment.dto.request.PaymentVerifyRequest;
import com.example.ossdoc.domain.payment.dto.request.TokenChargeCheckoutRequest;
import com.example.ossdoc.domain.payment.dto.response.PaymentCancelResponse;
import com.example.ossdoc.domain.payment.dto.response.PaymentVerifyResponse;
import com.example.ossdoc.domain.payment.dto.response.PortOneCheckoutResponse;
import com.example.ossdoc.domain.payment.entity.PaymentOrder;
import com.example.ossdoc.domain.payment.exception.PaymentException;
import com.example.ossdoc.domain.payment.exception.code.PaymentErrorCode;
import com.example.ossdoc.domain.payment.repository.PaymentOrderRepository;
import com.example.ossdoc.domain.token.dto.response.TokenBalanceResponse;
import com.example.ossdoc.domain.token.enums.TokenReferenceType;
import com.example.ossdoc.domain.token.service.TokenService;
import com.example.ossdoc.domain.user.entity.User;
import com.example.ossdoc.domain.user.repository.UserRepository;
import com.example.ossdoc.global.properties.PortOneProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentService {

    private static final String TOKEN_CURRENCY = "KRW";

    private static final String PAID_STATUS = "PAID";
    private static final String CANCELED_STATUS = "CANCELLED";
    private static final String CANCELED_STATUS_ALT = "CANCELED";

    private final UserRepository userRepository;
    private final PaymentOrderRepository paymentOrderRepository;
    private final PortOnePaymentClient portOnePaymentClient;
    private final PortOneProperties portOneProperties;
    private final TokenService tokenService;
    private final ObjectMapper objectMapper;

    /*
     * 토큰 충전 결제창 호출 정보를 준비합니다.
     * 1 KRW = 1 Token이므로 amount와 tokenAmount는 동일합니다.
     */
    @Transactional
    public PortOneCheckoutResponse prepareTokenCheckout(
            Long userId,
            TokenChargeCheckoutRequest request
    ) {
        portOneProperties.validateCheckoutConfig();

        User user = getActiveUser(userId);

        int amount = request.amount();
        int tokenAmount = amount;

        String paymentId = generatePaymentId();
        String orderName = "OSS Doc " + tokenAmount + " 토큰 충전";
        String customerKey = "user_" + user.getId();

        PaymentOrder order = PaymentOrder.readyForTokenCharge(
                user,
                paymentId,
                orderName,
                amount,
                tokenAmount,
                TOKEN_CURRENCY,
                customerKey
        );

        paymentOrderRepository.save(order);

        return new PortOneCheckoutResponse(
                paymentId,
                PaymentProvider.PORTONE_V2,
                portOneProperties.getStoreId(),
                portOneProperties.getChannelKey(),
                amount,
                tokenAmount,
                TOKEN_CURRENCY,
                orderName,
                customerKey,
                user.getEmail(),
                user.getNickname()
        );
    }

    /*
     * PortOne 결제 단건 조회로 결제 완료 여부를 검증한 뒤,
     * 결제 금액만큼 토큰을 충전합니다.
     */
    @Transactional
    public PaymentVerifyResponse verifyTokenPayment(
            Long userId,
            PaymentVerifyRequest request
    ) {
        User user = getActiveUser(userId);
        PaymentOrder order = getMyPaymentOrder(request.paymentId(), user);

        return syncPaidTokenPayment(order);
    }

    @Transactional
    public PaymentCancelResponse cancelPayment(
            Long userId,
            String paymentId,
            PaymentCancelRequest request
    ) {
        User user = getActiveUser(userId);
        PaymentOrder order = getMyPaymentOrder(paymentId, user);

        if (order.isCanceled()) {
            return new PaymentCancelResponse(
                    order.getPaymentId(),
                    order.getStatus(),
                    order.getCanceledAt(),
                    "이미 취소된 결제입니다."
            );
        }

        String reason = request == null
                || request.reason() == null
                || request.reason().isBlank()
                ? "테스트 결제 취소"
                : request.reason();

        /*
         * 현재 3차에서는 결제 취소 시 토큰 차감 회수 정책은 최소화합니다.
         * 실서비스에서는 이미 사용한 토큰이 있는 경우 취소 가능 여부를 별도로 막아야 합니다.
         */
        PortOnePaymentSnapshot snapshot = portOnePaymentClient.cancelPayment(paymentId, reason);

        order.markCanceled(
                snapshot.status(),
                reason,
                snapshot.canceledAt()
        );

        return new PaymentCancelResponse(
                order.getPaymentId(),
                order.getStatus(),
                order.getCanceledAt(),
                "결제가 취소되었습니다."
        );
    }

    /*
     * Webhook으로 결제 상태가 들어온 경우에도 서버 기준으로 결제 단건 조회 후 동기화합니다.
     */
    @Transactional
    public void handleWebhook(String rawBody) {
        String paymentId = extractPaymentId(rawBody);

        if (paymentId == null || paymentId.isBlank()) {
            return;
        }

        PaymentOrder order = paymentOrderRepository.findByPaymentId(paymentId)
                .orElse(null);

        if (order == null) {
            return;
        }

        PortOnePaymentSnapshot snapshot = portOnePaymentClient.getPayment(paymentId);

        if (isPaid(snapshot.status())) {
            syncPaidTokenPayment(order);
            return;
        }

        if (isCanceled(snapshot.status())) {
            order.markCanceled(
                    snapshot.status(),
                    "PortOne 웹훅 결제 취소 동기화",
                    snapshot.canceledAt()
            );
            return;
        }

        order.markFailed(
                snapshot.status(),
                "PortOne 웹훅 결제 실패 또는 미완료 상태"
        );
    }

    private PaymentVerifyResponse syncPaidTokenPayment(PaymentOrder order) {
        if (order.isPaid()) {
            TokenBalanceResponse balance = tokenService.getMyBalance(order.getUser().getId());

            return new PaymentVerifyResponse(
                    order.getPaymentId(),
                    order.getStatus(),
                    order.getAmount(),
                    order.getTokenAmount(),
                    balance.balance(),
                    order.getCurrency(),
                    "이미 검증 완료된 결제입니다."
            );
        }

        PortOnePaymentSnapshot snapshot = portOnePaymentClient.getPayment(order.getPaymentId());

        validatePaidSnapshot(order, snapshot);

        order.markPaid(
                snapshot.status(),
                snapshot.transactionId(),
                snapshot.paidAt()
        );

        TokenBalanceResponse balance = tokenService.chargeTokens(
                order.getUser().getId(),
                order.getTokenAmount(),
                TokenReferenceType.PAYMENT,
                order.getPaymentId(),
                "PortOne 토큰 충전 결제"
        );

        return new PaymentVerifyResponse(
                order.getPaymentId(),
                order.getStatus(),
                order.getAmount(),
                order.getTokenAmount(),
                balance.balance(),
                order.getCurrency(),
                "토큰 충전이 완료되었습니다."
        );
    }

    private void validatePaidSnapshot(
            PaymentOrder order,
            PortOnePaymentSnapshot snapshot
    ) {
        if (!isPaid(snapshot.status())) {
            order.markFailed(
                    snapshot.status(),
                    "결제가 완료되지 않았습니다."
            );
            throw new PaymentException(PaymentErrorCode.PAYMENT_NOT_PAID);
        }

        if (snapshot.amount() == null || snapshot.amount() != order.getAmount()) {
            throw new PaymentException(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH);
        }

        if (snapshot.currency() == null
                || !snapshot.currency().equalsIgnoreCase(order.getCurrency())) {
            throw new PaymentException(PaymentErrorCode.PAYMENT_CURRENCY_MISMATCH);
        }
    }

    private User getActiveUser(Long userId) {
        return userRepository.findById(userId)
                .filter(User::isActive)
                .orElseThrow(() -> new AuthException(AuthErrorCode.USER_NOT_FOUND));
    }

    private PaymentOrder getMyPaymentOrder(String paymentId, User user) {
        PaymentOrder order = paymentOrderRepository.findByPaymentId(paymentId)
                .orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_ORDER_NOT_FOUND));

        if (!order.getUser().getId().equals(user.getId())) {
            throw new PaymentException(PaymentErrorCode.PAYMENT_ACCESS_DENIED);
        }

        return order;
    }

    private String generatePaymentId() {
        String paymentId;

        do {
            paymentId = "token_" + System.currentTimeMillis()
                    + "_"
                    + UUID.randomUUID().toString().substring(0, 8);
        } while (paymentOrderRepository.existsByPaymentId(paymentId));

        return paymentId;
    }

    private boolean isPaid(String status) {
        return PAID_STATUS.equalsIgnoreCase(status);
    }

    private boolean isCanceled(String status) {
        return CANCELED_STATUS.equalsIgnoreCase(status)
                || CANCELED_STATUS_ALT.equalsIgnoreCase(status);
    }

    private String extractPaymentId(String rawBody) {
        try {
            JsonNode root = objectMapper.readTree(rawBody);

            JsonNode dataPaymentId = root.path("data").path("paymentId");
            if (!dataPaymentId.isMissingNode() && !dataPaymentId.isNull()) {
                return dataPaymentId.asText();
            }

            JsonNode paymentId = root.path("paymentId");
            if (!paymentId.isMissingNode() && !paymentId.isNull()) {
                return paymentId.asText();
            }

            return null;
        } catch (Exception e) {
            throw new PaymentException(PaymentErrorCode.PAYMENT_WEBHOOK_INVALID);
        }
    }
}