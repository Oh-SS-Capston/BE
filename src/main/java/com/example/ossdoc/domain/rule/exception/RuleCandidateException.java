package com.example.ossdoc.domain.rule.exception;

import com.example.ossdoc.domain.rule.exception.code.RuleCandidateErrorCode;
import com.example.ossdoc.global.apiPayload.code.BaseCode;
import com.example.ossdoc.global.apiPayload.exception.GeneralException;
import lombok.Getter;

@Getter
public class RuleCandidateException extends GeneralException {

    public RuleCandidateException(RuleCandidateErrorCode code) {
        super(code);
    }

    public RuleCandidateException(BaseCode code) {
        super(code);
    }

    public RuleCandidateException(RuleCandidateErrorCode code, String detailMessage) {
        super(code, detailMessage);
    }

    public RuleCandidateException(RuleCandidateErrorCode code, Throwable cause) {
        super(code, cause);
    }

    public RuleCandidateException(RuleCandidateErrorCode code, String detailMessage, Throwable cause) {
        super(code, detailMessage, cause);
    }
}