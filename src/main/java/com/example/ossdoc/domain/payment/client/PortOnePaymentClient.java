package com.example.ossdoc.domain.payment.client;

import com.example.ossdoc.domain.payment.dto.portone.PortOneCancelRequest;
import com.example.ossdoc.domain.payment.dto.portone.PortOnePaymentSnapshot;
import com.example.ossdoc.domain.payment.exception.PaymentException;
import com.example.ossdoc.domain.payment.exception.code.PaymentErrorCode;
import com.example.ossdoc.global.properties.PortOneProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

@Component
@RequiredArgsConstructor
public class PortOnePaymentClient {

    private final PortOneProperties portOneProperties;
    private final WebClient.Builder webClientBuilder;

    public PortOnePaymentSnapshot getPayment(String paymentId) {
        portOneProperties.validateApiConfig();

        try {
            JsonNode root = webClient()
                    .get()
                    .uri("/payments/{paymentId}", paymentId)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (root == null) {
                throw new PaymentException(PaymentErrorCode.PAYMENT_PROVIDER_ERROR);
            }

            return toSnapshot(root, paymentId);
        } catch (PaymentException e) {
            throw e;
        } catch (Exception e) {
            throw new PaymentException(PaymentErrorCode.PAYMENT_PROVIDER_ERROR);
        }
    }

    public PortOnePaymentSnapshot cancelPayment(String paymentId, String reason) {
        portOneProperties.validateApiConfig();

        try {
            JsonNode root = webClient()
                    .post()
                    .uri("/payments/{paymentId}/cancel", paymentId)
                    .bodyValue(new PortOneCancelRequest(reason))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (root == null) {
                throw new PaymentException(PaymentErrorCode.PAYMENT_CANCEL_FAILED);
            }

            return toSnapshot(root, paymentId);
        } catch (PaymentException e) {
            throw e;
        } catch (Exception e) {
            throw new PaymentException(PaymentErrorCode.PAYMENT_CANCEL_FAILED);
        }
    }

    private WebClient webClient() {
        return webClientBuilder
                .baseUrl(portOneProperties.getApiBaseUrl())
                .defaultHeader(
                        HttpHeaders.AUTHORIZATION,
                        "PortOne " + portOneProperties.getApiSecret()
                )
                .build();
    }

    private PortOnePaymentSnapshot toSnapshot(JsonNode root, String fallbackPaymentId) {
        String paymentId = text(root, "id", fallbackPaymentId);
        String status = text(root, "status", null);
        Integer amount = integer(root.path("amount"), "total");
        String currency = text(root, "currency", null);
        String transactionId = text(root, "transactionId", null);

        LocalDateTime paidAt = dateTime(root, "paidAt");
        LocalDateTime canceledAt = dateTime(root, "cancelledAt");

        return new PortOnePaymentSnapshot(
                paymentId,
                status,
                amount,
                currency,
                transactionId,
                paidAt,
                canceledAt
        );
    }

    private String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        return value.asText();
    }

    private Integer integer(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) {
            return null;
        }

        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }

        return value.asInt();
    }

    private LocalDateTime dateTime(JsonNode node, String field) {
        String value = text(node, field, null);

        if (value == null || value.isBlank()) {
            return null;
        }

        return OffsetDateTime.parse(value).toLocalDateTime();
    }
}