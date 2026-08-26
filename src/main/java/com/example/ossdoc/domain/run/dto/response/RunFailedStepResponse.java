package com.example.ossdoc.domain.run.dto.response;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class RunFailedStepResponse {

    private String stage;
    private String message;
}