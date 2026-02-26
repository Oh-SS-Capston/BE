// domain/run/exception/RunException.java
package com.example.ossdoc.domain.run.exception;

import lombok.Getter;

// ✅ 너희 프로젝트에 이미 GeneralException 같은 베이스가 있으면 그걸 상속하도록 바꿔 끼우면 됨.
@Getter
public class RunException extends RuntimeException {
    private final RunErrorCode errorCode;

    public RunException(RunErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public RunException(RunErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
}