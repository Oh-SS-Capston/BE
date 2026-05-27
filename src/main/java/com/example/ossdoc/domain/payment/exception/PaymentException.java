package com.example.ossdoc.domain.payment.exception;

import com.example.ossdoc.domain.payment.exception.code.PaymentErrorCode;
import com.example.ossdoc.global.apiPayload.code.BaseCode;
import com.example.ossdoc.global.apiPayload.exception.GeneralException;

public class PaymentException extends GeneralException {

    public PaymentException(PaymentErrorCode code) {
        super(code);
    }

    public PaymentException(BaseCode code) {
        super(code);
    }
}