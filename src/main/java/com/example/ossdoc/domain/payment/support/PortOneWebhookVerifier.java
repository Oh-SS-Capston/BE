package com.example.ossdoc.domain.payment.support;

import com.example.ossdoc.domain.payment.exception.PaymentException;
import com.example.ossdoc.domain.payment.exception.code.PaymentErrorCode;
import com.example.ossdoc.global.properties.PortOneProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

@Component
@RequiredArgsConstructor
public class PortOneWebhookVerifier {

    private static final long ALLOWED_TIMESTAMP_DRIFT_SECONDS = 60L * 5L;

    private final PortOneProperties portOneProperties;

    public void verify(String rawBody, HttpServletRequest request) {
        portOneProperties.validateWebhookConfig();

        String webhookId = request.getHeader("webhook-id");
        String webhookTimestamp = request.getHeader("webhook-timestamp");
        String webhookSignature = request.getHeader("webhook-signature");

        if (isBlank(webhookId) || isBlank(webhookTimestamp) || isBlank(webhookSignature)) {
            throw new PaymentException(PaymentErrorCode.PAYMENT_WEBHOOK_INVALID);
        }

        validateTimestamp(webhookTimestamp);

        String signedPayload = webhookId + "." + webhookTimestamp + "." + rawBody;
        byte[] expectedSignature = hmacSha256(
                portOneProperties.getWebhookSecret(),
                signedPayload
        );

        boolean matched = parseSignatures(webhookSignature).stream()
                .map(this::decodeBase64)
                .anyMatch(actual -> constantTimeEquals(expectedSignature, actual));

        if (!matched) {
            throw new PaymentException(PaymentErrorCode.PAYMENT_WEBHOOK_INVALID);
        }
    }

    private void validateTimestamp(String webhookTimestamp) {
        try {
            long timestamp = Long.parseLong(webhookTimestamp);
            long now = Instant.now().getEpochSecond();

            if (Math.abs(now - timestamp) > ALLOWED_TIMESTAMP_DRIFT_SECONDS) {
                throw new PaymentException(PaymentErrorCode.PAYMENT_WEBHOOK_INVALID);
            }
        } catch (NumberFormatException e) {
            throw new PaymentException(PaymentErrorCode.PAYMENT_WEBHOOK_INVALID);
        }
    }

    private List<String> parseSignatures(String webhookSignature) {
        return List.of(webhookSignature.split(" "))
                .stream()
                .flatMap(part -> List.of(part.split(",")).stream())
                .filter(part -> part.startsWith("v1,") || part.startsWith("v1="))
                .map(part -> part.substring(3))
                .filter(part -> !part.isBlank())
                .toList();
    }

    private byte[] hmacSha256(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"
            ));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new PaymentException(PaymentErrorCode.PAYMENT_WEBHOOK_INVALID);
        }
    }

    private byte[] decodeBase64(String value) {
        try {
            return Base64.getDecoder().decode(value);
        } catch (Exception e) {
            throw new PaymentException(PaymentErrorCode.PAYMENT_WEBHOOK_INVALID);
        }
    }

    private boolean constantTimeEquals(byte[] expected, byte[] actual) {
        if (expected == null || actual == null || expected.length != actual.length) {
            return false;
        }

        int result = 0;
        for (int i = 0; i < expected.length; i++) {
            result |= expected[i] ^ actual[i];
        }
        return result == 0;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}