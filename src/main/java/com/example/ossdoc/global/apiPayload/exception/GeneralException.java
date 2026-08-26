package com.example.ossdoc.global.apiPayload.exception;

import com.example.ossdoc.global.apiPayload.code.BaseCode;
import com.example.ossdoc.global.apiPayload.code.ReasonDTO;
import lombok.Getter;

@Getter
public class GeneralException extends RuntimeException {

    private final BaseCode code;
    private final String detailMessage;

    public GeneralException(BaseCode code) {
        this.code = code;
        this.detailMessage = null;
    }

    public GeneralException(BaseCode code, String detailMessage) {
        this.code = code;
        this.detailMessage = detailMessage;
    }

    public GeneralException(BaseCode code, Throwable cause) {
        super(cause);
        this.code = code;
        this.detailMessage = null;
    }

    public GeneralException(BaseCode code, String detailMessage, Throwable cause) {
        super(cause);
        this.code = code;
        this.detailMessage = detailMessage;
    }

    public ReasonDTO getErrorReason() {
        return this.code.getReason();
    }

    public ReasonDTO getErrorReasonHttpStatus() {
        return this.code.getReasonHttpStatus();
    }
}