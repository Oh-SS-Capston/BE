// 역할: class map 도메인 예외를 전역 예외 체계로 전달한다.
package com.example.ossdoc.domain.classmap.exception;

import com.example.ossdoc.domain.classmap.exception.code.ClassMapErrorCode;
import com.example.ossdoc.global.apiPayload.code.BaseCode;
import com.example.ossdoc.global.apiPayload.exception.GeneralException;

public class ClassMapException extends GeneralException {

    public ClassMapException(ClassMapErrorCode code) {
        super(code);
    }

    public ClassMapException(BaseCode code) {
        super(code);
    }
}
