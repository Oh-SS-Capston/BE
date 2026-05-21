package com.example.ossdoc.domain.auth.exception;

import com.example.ossdoc.domain.auth.exception.code.AuthErrorCode;
import com.example.ossdoc.global.apiPayload.code.BaseCode;
import com.example.ossdoc.global.apiPayload.exception.GeneralException;

public class AuthException extends GeneralException{
    public AuthException(BaseCode code) {
        super(code);
    }

    public AuthException(AuthErrorCode code) {
        super(code);
    }
}
