package com.example.ossdoc.domain.artifact.exception;

import com.example.ossdoc.domain.artifact.exception.code.ArtifactErrorCode;
import com.example.ossdoc.global.apiPayload.code.BaseCode;
import com.example.ossdoc.global.apiPayload.exception.GeneralException;

public class ArtifactException extends GeneralException {

    public ArtifactException(ArtifactErrorCode code) {
        super(code);
    }

    public ArtifactException(BaseCode code) {
        super(code);
    }
}