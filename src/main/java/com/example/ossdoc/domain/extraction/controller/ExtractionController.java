package com.example.ossdoc.domain.extraction.controller;

import com.example.ossdoc.domain.extraction.dto.request.FactsExtractRequest;
import com.example.ossdoc.domain.extraction.dto.response.FactsExtractResponse;
import com.example.ossdoc.domain.extraction.facade.FactsExtractionFacade;
import com.example.ossdoc.global.apiPayload.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/extraction")
public class ExtractionController {

    private final FactsExtractionFacade factsExtractionFacade;

    @PostMapping("/extract")
    public ApiResponse<FactsExtractResponse> extract(@Valid @RequestBody FactsExtractRequest request) {
        FactsExtractResponse response = factsExtractionFacade.extract(request);
        return ApiResponse.onSuccess(response);
    }
}