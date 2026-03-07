package com.example.ossdoc.domain.build.exception;

import com.example.ossdoc.domain.build.exception.code.BuildErrorCode;
import com.example.ossdoc.global.apiPayload.code.BaseCode;
import com.example.ossdoc.global.apiPayload.exception.GeneralException;

public class BuildException extends GeneralException {
    public BuildException(BuildErrorCode code) {
        super(code);
    }


    public BuildException(BaseCode code) {
        super(code);
    }
}
