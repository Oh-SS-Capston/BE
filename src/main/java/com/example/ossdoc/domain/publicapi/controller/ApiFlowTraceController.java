package com.example.ossdoc.domain.publicapi.controller;

import com.example.ossdoc.domain.publicapi.service.ApiFlowTraceService;
import com.example.ossdoc.global.apiPayload.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API 진입점 호출 경로 추적 컨트롤러.
 * POST /api/v1/apimap/flow-trace?runId=xxx
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/apimap")
public class ApiFlowTraceController {

    private final ApiFlowTraceService apiFlowTraceService;

    @PostMapping("/flow-trace")
    @Operation(summary = "API 진입점별 BFS 호출 경로 추적 및 API_FLOW_TRACE_JSON 생성")
    public ApiResponse<String> trace(@RequestParam String runId) {
        apiFlowTraceService.trace(runId);
        return ApiResponse.onSuccess("API_FLOW_TRACE_JSON 생성 완료. runId=" + runId);
    }
}
