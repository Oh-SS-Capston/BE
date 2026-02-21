package com.example.ossdoc.global.apiPayload.code;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReasonDTO {

    private HttpStatus httpStatus;

    private boolean isSuccess;
    private String code;
    private String message;

    public boolean getIsSuccess() {
        return isSuccess;
    }
}