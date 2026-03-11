package com.example.ossdoc.global.llm.exception;

import com.example.ossdoc.global.apiPayload.code.BaseCode;
import com.example.ossdoc.global.apiPayload.exception.GeneralException;
import com.example.ossdoc.global.llm.exception.code.LlmErrorCode;

public class LlmException extends GeneralException {

    public LlmException(LlmErrorCode code) {
        super(code);
    }

    public LlmException(BaseCode code) {
        super(code);
    }
}