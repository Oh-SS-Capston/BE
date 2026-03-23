package com.example.ossdoc.domain.extraction.exception;

import com.example.ossdoc.global.apiPayload.exception.GeneralException;
import lombok.Getter;

@Getter
public class ExtractionException extends GeneralException {

    private final String detailMessage;

    public ExtractionException(ExtractionErrorCode errorCode) {
        super(errorCode);
        this.detailMessage = null;
    }

    public ExtractionException(ExtractionErrorCode errorCode, String detailMessage) {
        super(errorCode);
        this.detailMessage = detailMessage;
    }

    public ExtractionException(ExtractionErrorCode errorCode, Throwable cause) {
        super(errorCode);
        this.detailMessage = null;
        initCause(cause);
    }

    public ExtractionException(ExtractionErrorCode errorCode, String detailMessage, Throwable cause) {
        super(errorCode);
        this.detailMessage = detailMessage;
        initCause(cause);
    }
}