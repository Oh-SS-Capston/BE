package com.example.ossdoc.domain.payment.controller;

import com.example.ossdoc.domain.auth.exception.AuthException;
import com.example.ossdoc.domain.auth.exception.code.AuthErrorCode;
import com.example.ossdoc.domain.payment.dto.request.PaymentCancelRequest;
import com.example.ossdoc.domain.payment.dto.request.PaymentVerifyRequest;
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

    @PostMapping("/checkout")
    public ApiResponse<PortOneCheckoutResponse> prepareCheckout(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser
    ) {
        if (authenticatedUser == null) {
            throw new AuthException(AuthErrorCode.AUTHENTICATION_REQUIRED);
        }

        return ApiResponse.onSuccess(
                paymentService.prepareCheckout(authenticatedUser.getUserId())
        );
    }

    @PostMapping("/verify")
    public ApiResponse<PaymentVerifyResponse> verify(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @Valid @RequestBody PaymentVerifyRequest request
    ) {
        if (authenticatedUser == null) {
            throw new AuthException(AuthErrorCode.AUTHENTICATION_REQUIRED);
        }

        return ApiResponse.onSuccess(
                paymentService.verifyPayment(authenticatedUser.getUserId(), request)
        );
    }

    @PostMapping("/{paymentId}/cancel")
    public ApiResponse<PaymentCancelResponse> cancel(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable String paymentId,
            @RequestBody(required = false) PaymentCancelRequest request
    ) {
        if (authenticatedUser == null) {
            throw new AuthException(AuthErrorCode.AUTHENTICATION_REQUIRED);
        }

        return ApiResponse.onSuccess(
                paymentService.cancelPayment(
                        authenticatedUser.getUserId(),
                        paymentId,
                        request
                )
        );
    }

    @PostMapping("/webhook")
    public ApiResponse<String> webhook(
            @RequestBody String rawBody,
            HttpServletRequest request
    ) {
        webhookVerifier.verify(rawBody, request);
        paymentService.handleWebhook(rawBody);
        return ApiResponse.onSuccess("OK");
    }
}