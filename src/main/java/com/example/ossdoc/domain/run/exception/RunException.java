// domain/run/exception/RunException.java
package com.example.ossdoc.domain.run.exception;

import com.example.ossdoc.domain.run.exception.code.RunErrorCode;
import com.example.ossdoc.global.apiPayload.code.BaseCode;
import com.example.ossdoc.global.apiPayload.exception.GeneralException;
import lombok.Getter;

// ✅ 너희 프로젝트에 이미 GeneralException 같은 베이스가 있으면 그걸 상속하도록 바꿔 끼우면 됨.
@Getter
public class RunException extends GeneralException {

    public RunException(RunErrorCode code) {
        super(code);
    }

    public RunException(BaseCode code) {
        super(code);
    }
}