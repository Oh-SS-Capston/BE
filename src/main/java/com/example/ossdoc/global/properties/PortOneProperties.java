package com.example.ossdoc.global.properties;

import com.example.ossdoc.domain.payment.exception.PaymentException;
import com.example.ossdoc.domain.payment.exception.code.PaymentErrorCode;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "portone")
public class PortOneProperties {

    private String env = "test";

    private String storeId;

    private String channelKey;

    private String apiSecret;

    private String webhookSecret;

    private String apiBaseUrl = "https://api.portone.io";

    public void validateCheckoutConfig() {
        if (isBlank(storeId) || isBlank(channelKey)) {
            throw new PaymentException(PaymentErrorCode.PAYMENT_CONFIG_MISSING);
        }
    }

    public void validateApiConfig() {
        if (isBlank(apiSecret) || isBlank(apiBaseUrl)) {
            throw new PaymentException(PaymentErrorCode.PAYMENT_CONFIG_MISSING);
        }
    }

    public void validateWebhookConfig() {
        if (isBlank(webhookSecret)) {
            throw new PaymentException(PaymentErrorCode.PAYMENT_CONFIG_MISSING);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}