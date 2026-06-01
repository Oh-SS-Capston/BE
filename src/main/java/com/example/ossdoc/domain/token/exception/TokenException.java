package com.example.ossdoc.domain.token.exception;

import com.example.ossdoc.domain.token.exception.code.TokenErrorCode;
import com.example.ossdoc.global.apiPayload.code.BaseCode;
import com.example.ossdoc.global.apiPayload.exception.GeneralException;

public class TokenException extends GeneralException {

    public TokenException(TokenErrorCode code) {
        super(code);
    }

    public TokenException(TokenErrorCode code, String detailMessage) {
        super(code, detailMessage);
    }

    public TokenException(BaseCode code) {
        super(code);
    }
}