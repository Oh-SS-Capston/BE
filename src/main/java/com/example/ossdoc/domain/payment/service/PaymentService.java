package com.example.ossdoc.domain.payment.service;

import com.example.ossdoc.domain.auth.exception.AuthException;
import com.example.ossdoc.domain.auth.exception.code.AuthErrorCode;
import com.example.ossdoc.domain.membership.entity.UserSubscription;
import com.example.ossdoc.domain.membership.enums.MembershipPlan;
import com.example.ossdoc.domain.membership.enums.PaymentProvider;
import com.example.ossdoc.domain.membership.enums.SubscriptionStatus;
import com.example.ossdoc.domain.membership.exception.MembershipException;
import com.example.ossdoc.domain.membership.exception.code.MembershipErrorCode;
import com.example.ossdoc.domain.membership.repository.UserSubscriptionRepository;
import com.example.ossdoc.domain.membership.service.MembershipAccessService;
import com.example.ossdoc.domain.payment.client.PortOnePaymentClient;
import com.example.ossdoc.domain.payment.dto.portone.PortOnePaymentSnapshot;
import com.example.ossdoc.domain.payment.dto.request.PaymentCancelRequest;
import com.example.ossdoc.domain.payment.dto.request.PaymentVerifyRequest;
import com.example.ossdoc.domain.payment.dto.response.PaymentCancelResponse;
import com.example.ossdoc.domain.payment.dto.response.PaymentVerifyResponse;
import com.example.ossdoc.domain.payment.dto.response.PortOneCheckoutResponse;
import com.example.ossdoc.domain.payment.entity.PaymentOrder;
import com.example.ossdoc.domain.payment.enums.PaymentStatus;
import com.example.ossdoc.domain.payment.exception.PaymentException;
import com.example.ossdoc.domain.payment.exception.code.PaymentErrorCode;
import com.example.ossdoc.domain.payment.repository.PaymentOrderRepository;
import com.example.ossdoc.domain.user.entity.User;
import com.example.ossdoc.domain.user.repository.UserRepository;
import com.example.ossdoc.global.properties.PortOneProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentService {

    private static final MembershipPlan DEFAULT_PLAN = MembershipPlan.BASIC_MONTHLY;
    private static final String PAID_STATUS = "PAID";
    private static final String CANCELED_STATUS = "CANCELLED";
    private static final String CANCELED_STATUS_ALT = "CANCELED";

    private final UserRepository userRepository;
    private final PaymentOrderRepository paymentOrderRepository;
    private final UserSubscriptionRepository userSubscriptionRepository;
    private final MembershipAccessService membershipAccessService;
    private final PortOnePaymentClient portOnePaymentClient;
    private final PortOneProperties portOneProperties;
    private final ObjectMapper objectMapper;

    @Transactional
    public PortOneCheckoutResponse prepareCheckout(Long userId) {
        portOneProperties.validateCheckoutConfig();

        User user = getActiveUser(userId);

        if (membershipAccessService.hasActiveMembership(user)) {
            throw new MembershipException(MembershipErrorCode.SUBSCRIPTION_ALREADY_ACTIVE);
        }

        String paymentId = generatePaymentId();
        String orderName = DEFAULT_PLAN.getDisplayName() + " 정기 멤버십";
        String customerKey = "user_" + user.getId();

        PaymentOrder order = PaymentOrder.ready(
                user,
                paymentId,
                DEFAULT_PLAN,
                orderName,
                DEFAULT_PLAN.getAmount(),
                DEFAULT_PLAN.getCurrency(),
                customerKey
        );

        paymentOrderRepository.save(order);

        return new PortOneCheckoutResponse(
                paymentId,
                PaymentProvider.PORTONE_V2,
                portOneProperties.getStoreId(),
                portOneProperties.getChannelKey(),
                DEFAULT_PLAN,
                DEFAULT_PLAN.getDisplayName(),
                DEFAULT_PLAN.getAmount(),
                DEFAULT_PLAN.getCurrency(),
                orderName,
                customerKey,
                user.getEmail(),
                user.getNickname()
        );
    }

    @Transactional
    public PaymentVerifyResponse verifyPayment(
            Long userId,
            PaymentVerifyRequest request
    ) {
        User user = getActiveUser(userId);
        PaymentOrder order = getMyPaymentOrder(request.paymentId(), user);

        return syncPaidPayment(order);
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

        String reason = request == null || request.reason() == null || request.reason().isBlank()
                ? "테스트 결제 취소"
                : request.reason();

        PortOnePaymentSnapshot snapshot = portOnePaymentClient.cancelPayment(paymentId, reason);

        order.markCanceled(
                snapshot.status(),
                reason,
                snapshot.canceledAt()
        );

        userSubscriptionRepository.findFirstByLastPaymentId(order.getPaymentId())
                .ifPresent(UserSubscription::expire);

        return new PaymentCancelResponse(
                order.getPaymentId(),
                order.getStatus(),
                order.getCanceledAt(),
                "결제가 취소되었습니다."
        );
    }

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
            syncPaidPayment(order);
            return;
        }

        if (isCanceled(snapshot.status())) {
            order.markCanceled(
                    snapshot.status(),
                    "PortOne 웹훅 결제 취소 동기화",
                    snapshot.canceledAt()
            );

            userSubscriptionRepository.findFirstByLastPaymentId(order.getPaymentId())
                    .ifPresent(UserSubscription::expire);
            return;
        }

        order.markFailed(
                snapshot.status(),
                "PortOne 웹훅 결제 실패 또는 미완료 상태"
        );
    }

    private PaymentVerifyResponse syncPaidPayment(PaymentOrder order) {
        if (order.isPaid()) {
            UserSubscription subscription = userSubscriptionRepository
                    .findFirstByLastPaymentId(order.getPaymentId())
                    .orElse(null);

            return new PaymentVerifyResponse(
                    order.getPaymentId(),
                    order.getStatus(),
                    order.getPlan(),
                    order.getPlan().getDisplayName(),
                    order.getAmount(),
                    order.getCurrency(),
                    subscription != null && subscription.isActiveAt(LocalDateTime.now()),
                    subscription == null ? null : subscription.getCurrentPeriodEnd(),
                    "이미 검증 완료된 결제입니다."
            );
        }

        PortOnePaymentSnapshot snapshot = portOnePaymentClient.getPayment(order.getPaymentId());

        validatePaidSnapshot(order, snapshot);

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime periodEnd = now.plusMonths(order.getPlan().getBillingCycleMonths());

        order.markPaid(
                snapshot.status(),
                snapshot.transactionId(),
                snapshot.paidAt()
        );

        UserSubscription subscription = userSubscriptionRepository
                .findFirstByUserOrderByCreatedAtDesc(order.getUser())
                .orElseGet(() -> userSubscriptionRepository.save(
                        UserSubscription.pending(
                                order.getUser(),
                                order.getPlan(),
                                PaymentProvider.PORTONE_V2
                        )
                ));

        subscription.activateByPayment(order.getPaymentId(), now, periodEnd);

        return new PaymentVerifyResponse(
                order.getPaymentId(),
                order.getStatus(),
                order.getPlan(),
                order.getPlan().getDisplayName(),
                order.getAmount(),
                order.getCurrency(),
                true,
                periodEnd,
                "멤버십이 활성화되었습니다."
        );
    }

    private void validatePaidSnapshot(PaymentOrder order, PortOnePaymentSnapshot snapshot) {
        if (!isPaid(snapshot.status())) {
            order.markFailed(snapshot.status(), "결제가 완료되지 않았습니다.");
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
            paymentId = "membership_" + System.currentTimeMillis()
                    + "_" + UUID.randomUUID().toString().substring(0, 8);
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