package com.example.ossdoc.domain.payment.controller;

import com.example.ossdoc.domain.auth.exception.AuthException;
import com.example.ossdoc.domain.auth.exception.code.AuthErrorCode;
import com.example.ossdoc.domain.payment.dto.request.PaymentCancelRequest;
import com.example.ossdoc.domain.payment.dto.request.PaymentVerifyRequest;
import com.example.ossdoc.domain.payment.dto.request.TokenChargeCheckoutRequest;
import com.example.ossdoc.domain.payment.dto.response.PaymentCancelResponse;
import com.example.ossdoc.domain.payment.dto.response.PaymentVerifyResponse;
import com.example.ossdoc.domain.payment.dto.response.PortOneCheckoutResponse;
import com.example.ossdoc.domain.payment.service.PaymentService;
import com.example.ossdoc.domain.payment.support.PortOneWebhookVerifier;
import com.example.ossdoc.global.apiPayload.ApiResponse;
import com.example.ossdoc.global.security.jwt.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/payments/portone")
public class PaymentController {

    private final PaymentService paymentService;
    private final PortOneWebhookVerifier webhookVerifier;

    /*
     * 토큰 충전 결제창 호출에 필요한 정보를 생성합니다.
     *
     * POST /api/v1/payments/portone/token-checkout
     */
    @PostMapping("/token-checkout")
    public ApiResponse<PortOneCheckoutResponse> prepareTokenCheckout(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @Valid @RequestBody TokenChargeCheckoutRequest request
    ) {
        validateAuthenticated(authenticatedUser);

        return ApiResponse.onSuccess(
                paymentService.prepareTokenCheckout(
                        authenticatedUser.getUserId(),
                        request
                )
        );
    }

    /*
     * PortOne 결제 성공 후 서버에서 결제 단건 조회를 통해 검증하고,
     * 결제 금액만큼 토큰을 충전합니다.
     *
     * POST /api/v1/payments/portone/token-verify
     */
    @PostMapping("/token-verify")
    public ApiResponse<PaymentVerifyResponse> verifyTokenPayment(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @Valid @RequestBody PaymentVerifyRequest request
    ) {
        validateAuthenticated(authenticatedUser);

        return ApiResponse.onSuccess(
                paymentService.verifyTokenPayment(
                        authenticatedUser.getUserId(),
                        request
                )
        );
    }

    /*
     * PortOne Webhook 수신 API입니다.
     */
    @PostMapping("/webhook")
    public ApiResponse<String> webhook(
            @RequestBody String rawBody,
            HttpServletRequest request
    ) {
        webhookVerifier.verify(rawBody, request);
        paymentService.handleWebhook(rawBody);

        return ApiResponse.onSuccess("OK");
    }

    private void validateAuthenticated(AuthenticatedUser authenticatedUser) {
        if (authenticatedUser == null) {
            throw new AuthException(AuthErrorCode.AUTHENTICATION_REQUIRED);
        }
    }
}