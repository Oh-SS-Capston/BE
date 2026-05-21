package com.example.ossdoc.domain.membership.exception;

import com.example.ossdoc.domain.membership.exception.code.MembershipErrorCode;
import com.example.ossdoc.global.apiPayload.code.BaseCode;
import com.example.ossdoc.global.apiPayload.exception.GeneralException;

public class MembershipException extends GeneralException {

    public MembershipException(MembershipErrorCode code) {
        super(code);
    }

    public MembershipException(BaseCode code) {
        super(code);
    }
}