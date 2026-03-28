package com.example.ossdoc.domain.extraction.exception;

import com.example.ossdoc.global.apiPayload.exception.GeneralException;

public class ExtractionException extends GeneralException {

    public ExtractionException(ExtractionErrorCode errorCode) {
        super(errorCode);
    }

    public ExtractionException(ExtractionErrorCode errorCode, String detailMessage) {
        super(errorCode, detailMessage);
    }

    public ExtractionException(ExtractionErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }

    public ExtractionException(ExtractionErrorCode errorCode, String detailMessage, Throwable cause) {
        super(errorCode, detailMessage, cause);
    }
}