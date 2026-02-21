package com.example.ossdoc.global.apiPayload.exception.handler;


import com.example.ossdoc.global.apiPayload.code.BaseCode;
import com.example.ossdoc.global.apiPayload.exception.GeneralException;

public class ErrorHandler extends GeneralException {
    public ErrorHandler(BaseCode errorCode) {
        super(errorCode);
    }
}

