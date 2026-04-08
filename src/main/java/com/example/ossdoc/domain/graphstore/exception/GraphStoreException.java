package com.example.ossdoc.domain.graphstore.exception;

import com.example.ossdoc.domain.graphstore.exception.code.GraphStoreErrorCode;
import com.example.ossdoc.global.apiPayload.code.BaseCode;
import com.example.ossdoc.global.apiPayload.exception.GeneralException;
import lombok.Getter;

@Getter
public class GraphStoreException extends GeneralException {

    public GraphStoreException(GraphStoreErrorCode code) { super(code); }

    public GraphStoreException(BaseCode code) { super(code); }
}