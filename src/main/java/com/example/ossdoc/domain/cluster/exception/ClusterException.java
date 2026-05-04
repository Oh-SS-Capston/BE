package com.example.ossdoc.domain.cluster.exception;

import com.example.ossdoc.domain.cluster.exception.code.ClusterErrorCode;
import com.example.ossdoc.global.apiPayload.code.BaseCode;
import com.example.ossdoc.global.apiPayload.exception.GeneralException;

public class ClusterException extends GeneralException {

    public ClusterException(ClusterErrorCode code) {
        super(code);
    }

    public ClusterException(BaseCode code) {
        super(code);
    }
}