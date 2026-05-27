package com.example.ossdoc.domain.payment.client;

import com.example.ossdoc.domain.payment.dto.portone.PortOneCancelRequest;
import com.example.ossdoc.domain.payment.dto.portone.PortOnePaymentSnapshot;
import com.example.ossdoc.domain.payment.exception.PaymentException;
import com.example.ossdoc.domain.payment.exception.code.PaymentErrorCode;
import com.example.ossdoc.global.properties.PortOneProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class PortOnePaymentClient {

    private final PortOneProperties portOneProperties;
    private final WebClient.Builder webClientBuilder;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public PortOnePaymentSnapshot getPayment(String paymentId) {
        portOneProperties.validateApiConfig();

        try {
            String body = webClient()
                    .get()
                    .uri("/payments/{paymentId}", paymentId)
                    .exchangeToMono(response -> response.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(responseBody -> {
                                if (response.statusCode().is2xxSuccessful()) {
                                    return responseBody;
                                }

                                log.error(
                                        "PortOne payment lookup failed. status={}, paymentId={}, body={}",
                                        response.statusCode(),
                                        paymentId,
                                        responseBody
                                );

                                throw new PaymentException(PaymentErrorCode.PAYMENT_PROVIDER_ERROR);
                            }))
                    .block();

            if (body == null || body.isBlank()) {
                log.error("PortOne payment lookup returned empty body. paymentId={}", paymentId);
                throw new PaymentException(PaymentErrorCode.PAYMENT_PROVIDER_ERROR);
            }

            JsonNode root = objectMapper.readTree(body);

            log.info(
                    "PortOne payment lookup success. paymentId={}, status={}, amount={}, currency={}",
                    paymentId,
                    text(root, "status", null),
                    integer(root.path("amount"), "total"),
                    text(root, "currency", null)
            );

            return toSnapshot(root, paymentId);
        } catch (PaymentException e) {
            throw e;
        } catch (Exception e) {
            log.error("PortOne payment lookup unexpected error. paymentId={}", paymentId, e);
            throw new PaymentException(PaymentErrorCode.PAYMENT_PROVIDER_ERROR);
        }
    }

    public PortOnePaymentSnapshot cancelPayment(String paymentId, String reason) {
        portOneProperties.validateApiConfig();

        try {
            String body = webClient()
                    .post()
                    .uri("/payments/{paymentId}/cancel", paymentId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(new PortOneCancelRequest(reason))
                    .exchangeToMono(response -> response.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(responseBody -> {
                                if (response.statusCode().is2xxSuccessful()) {
                                    return responseBody;
                                }

                                log.error(
                                        "PortOne payment cancel failed. status={}, paymentId={}, body={}",
                                        response.statusCode(),
                                        paymentId,
                                        responseBody
                                );

                                throw new PaymentException(PaymentErrorCode.PAYMENT_CANCEL_FAILED);
                            }))
                    .block();

            if (body == null || body.isBlank()) {
                log.error("PortOne payment cancel returned empty body. paymentId={}", paymentId);
                throw new PaymentException(PaymentErrorCode.PAYMENT_CANCEL_FAILED);
            }

            JsonNode root = objectMapper.readTree(body);

            return toSnapshot(root, paymentId);
        } catch (PaymentException e) {
            throw e;
        } catch (Exception e) {
            log.error("PortOne payment cancel unexpected error. paymentId={}", paymentId, e);
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
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
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