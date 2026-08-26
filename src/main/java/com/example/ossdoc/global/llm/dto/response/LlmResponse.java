package com.example.ossdoc.global.llm.dto.response;

import com.example.ossdoc.global.llm.dto.json.LlmResult;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class LlmResponse {

    private String runId;
    private LlmResult result;
}