package com.example.ossdoc.global.llm.controller;

import com.example.ossdoc.global.apiPayload.ApiResponse;
import com.example.ossdoc.global.llm.dto.request.LlmRequest;
import com.example.ossdoc.global.llm.dto.response.LlmResponse;
import com.example.ossdoc.global.llm.service.LlmService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/llm")
public class LlmController {

    private final LlmService llmService;

    @PostMapping("/refine")
    public ApiResponse<LlmResponse> refine(@Valid @RequestBody LlmRequest request) {
        LlmResponse response = llmService.refine(request);
        return ApiResponse.onSuccess(response);
    }
}