package com.example.ossdoc.global.s3.exception;

import com.example.ossdoc.global.apiPayload.exception.GeneralException;
import com.example.ossdoc.global.s3.exception.code.S3ErrorCode;

public class S3Exception extends GeneralException {

    public S3Exception(S3ErrorCode code) {
        super(code);
    }
}